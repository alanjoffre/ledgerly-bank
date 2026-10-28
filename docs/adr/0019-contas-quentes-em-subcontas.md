# ADR-0019: Dividir as contas quentes do razão em sub-contas

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Uma **conta quente** (*hot account*, no mercado) é uma conta que quase todo lançamento toca. No
razão são duas: o **Caixa**, que entra em todo depósito e todo saque, e a **Liquidação Pix**, que
entra em todo Pix (ADR-0015).

Cada lançamento trava as linhas das suas contas com `SELECT ... FOR UPDATE`, em ordem
(ADR-0009), e só solta no `COMMIT`. É o que garante que o saldo nunca é calculado em cima de um
valor velho. O efeito colateral: dois depósitos em contas de clientes **diferentes** ainda fazem
fila na mesma linha, a do Caixa. A vazão do razão inteiro fica limitada por uma linha, e o limite é
1 dividido pelo tempo em que a trava fica presa (que inclui a gravação em disco do `COMMIT`).

Medido com o Gatling (ADR-0021) antes de qualquer mudança: 64 clientes simultâneos por 30 s, na
mesma máquina e no mesmo razão.

| Cenário | Passa por conta quente? | Pedidos/s | Latência p50 | p99 |
| --- | --- | --- | --- | --- |
| Transferências entre clientes | não | **954** | 57 ms | 126 ms |
| Depósitos | Caixa | **124** | 448 ms | 1.030 ms |
| Envios de Pix | Liquidação Pix | **126** | 435 ms | 1.007 ms |

O mesmo banco faz 7,6 vezes mais trabalho quando os pedidos não disputam a mesma linha. O gargalo
não é o banco, é a linha.

## Decisão

Cada conta quente vira **16 sub-contas**, e cada lançamento trava uma só. O saldo da conta é a
soma das sub-contas. É a ideia do `LongAdder` do Java (medido no JMH, ADR-0023): em vez de um
contador disputado, vários, somados na leitura.

- **Qual sub-conta:** a do cliente, pelo hash do id da conta dele
  (`Math.floorMod(conta.hashCode(), 16)`). Dois pedidos do mesmo cliente já fariam fila na conta
  dele de qualquer jeito; clientes diferentes se espalham.
- **Os ids:** toda conta interna tem os 64 bits altos zerados. O último byte é a conta-mãe
  (`01` = Caixa, `02` = Liquidação Pix) e o penúltimo, a sub-conta: `...000000000301` é a
  sub-conta 3 do Caixa. A sub-conta 0 é o id antigo da conta, então o histórico continua válido.
- **Configurável:** `RAZAO_SUBCONTAS` (de 1 a 16; padrão 16). Com 1, o razão volta a se comportar
  como antes, o que permitiu medir o antes e o depois com o mesmo código.

**A regra de saldo é diferente em cada conta**, porque o dinheiro anda de jeitos diferentes:

| Conta | Sub-conta pode ficar negativa? | Por quê |
| --- | --- | --- |
| **Caixa** | **sim** | Um cliente deposita na sub-conta 3 e outro saca da 7: a 7 fica negativa e a soma está certa. A soma nunca fica negativa pela própria contabilidade: o Caixa é a única conta devedora, então Caixa = clientes + Liquidação Pix, e essas nunca ficam negativas. A conferência de integridade (`GET /v1/integridade`) confere a soma e conta as sub-contas negativas. É um **controle detectivo** (acha o erro depois). |
| **Liquidação Pix** | **não** | O recebimento e o estorno saem da **mesma** sub-conta em que o envio entrou. Para isso, o pedido aponta o lançamento do envio (`lancamentoEnvioId`), e o razão lê nele a sub-conta. Cada sub-conta continua com o `CHECK` de saldo não negativo. É um **controle preventivo** (impede o erro). |

### Um buraco que apareceu no caminho, e foi fechado

Para a saída da Liquidação apontar o envio, foi preciso perguntar: o que impede de apontar o mesmo
envio duas vezes? **Nada impedia, nem antes da divisão.** O razão aceitava receber **e também**
estornar o mesmo envio, desde que a Liquidação Pix tivesse dinheiro de outros Pix em andamento: o
pagador recebia de volta, o recebedor recebia também, e o dinheiro aparecia do nada. Só a Saga do
Pix (ADR-0015) impedia, porque ela nunca pede os dois. Isso é uma defesa só, e o razão é quem
guarda o dinheiro.

Agora, **no máximo uma saída por envio**, em duas camadas:

1. **Na aplicação**, depois da trava: o envio já teve saída? Então `422 envio-pix-invalido`. O
   razão também confere se o lançamento apontado é mesmo um envio, se o valor é o mesmo e se o
   estorno volta para quem pagou.
2. **No banco**, um índice único parcial:
   `CREATE UNIQUE INDEX uma_saida_por_envio ON lancamento (envio_pix_id) WHERE envio_pix_id IS NOT NULL`.
   Hoje o recebimento e o estorno de um mesmo envio travam a mesma sub-conta da Liquidação, então
   fazem fila e o segundo já vê o primeiro. O índice é a defesa em profundidade: se um erro futuro
   deixar os dois passarem pela conferência da aplicação, o segundo `INSERT` é recusado pelo banco.

A prova: `PixIT` dispara o recebimento e o estorno do mesmo envio **ao mesmo tempo** e exige que
exatamente um passe; `BancoDeDadosIT` grava a segunda saída por SQL direto, sem passar pela
aplicação, e o banco recusa.

## Números (depois)

Mesma carga, mesmo código, na mesma sessão, mudando só `RAZAO_SUBCONTAS`:

| Cenário | 1 sub-conta | 16 sub-contas | Ganho |
| --- | --- | --- | --- |
| Depósitos (pedidos/s) | 99 | **489** | **5,0×** |
| Envios de Pix (pedidos/s) | 114 | **543** | **4,8×** |
| Depósitos, latência p99 | 1.043 ms | **288 ms** | |
| Envios de Pix, latência p99 | 1.014 ms | **230 ms** | |

Transferências, que não passam por conta quente, ficaram em 707 pedidos/s na mesma sessão. As
contas quentes chegaram a uns 70% a 77% do que o razão faz sem conta quente nenhuma. Zero erros e a
integridade conferida depois de cada rodada.

Os 1 sub-conta desta sessão (99 e 114) ficaram abaixo do "antes" (124 e 126): a máquina é a mesma,
mas roda outros contêineres, e estava mais carregada. Por isso a comparação que vale é a da mesma
sessão. Os arquivos de cada medição estão em `desempenho/resultados/carga/`.

**Por que não 16×:** o gargalo saiu da linha e foi para o resto do caminho (o pool de 20 conexões
com o banco, a gravação do `COMMIT` em disco, a própria máquina). É o que se espera: depois de tirar
um gargalo, aparece o próximo.

## Alternativas consideradas

- **Não escriturar o Caixa a cada lançamento** (acumular e lançar o total de tempos em tempos) —
  muito usado em bancos grandes, mas o saldo do Caixa ficaria atrasado e o lançamento deixaria de
  ter as duas pernas na mesma transação. Perde-se a regra de partidas dobradas por lançamento
  (ADR-0009), que é a garantia mais forte do razão.
- **Trava otimista** (versão na linha, tentar de novo se mudou) — numa linha disputada por todos,
  quase toda tentativa falha e se repete. Pior que a fila.
- **Sub-conta sorteada a cada pedido**, em vez de pelo cliente — espalha igual, mas um mesmo
  cliente passaria a travar sub-contas diferentes, e o estorno do Pix não saberia de onde tirar.
- **Mais sub-contas (64, 256)** — 16 já tirou a linha do caminho. Mais sub-contas são mais linhas
  para somar na integridade, sem ganho medido. O limite está na migração V3 e na constante
  `ContasQuentes.MAXIMO_DE_SUBCONTAS`.

## Consequências

- **Positivas:** depósitos e Pix quase 5× mais rápidos; a latência p99 caiu para um quarto; o razão
  ganhou a regra "uma saída por envio" que faltava.
- **Negativas:**
  - o saldo do Caixa e da Liquidação Pix agora é uma soma (`GET /v1/integridade` mostra);
  - uma sub-conta do Caixa negativa é normal, e quem olha o banco precisa saber disso (está na
    migração V3 e aqui);
  - o pedido de recebimento e de estorno mudou: agora exige `lancamentoEnvioId`. O único cliente é
    o serviço de Pix, que foi atualizado junto. O evento `LancamentoRegistrado` não mudou.
- A ADR-0009 (trava pessimista ordenada) continua valendo inteira: esta ADR muda **quais** linhas
  são travadas, não **como**.
