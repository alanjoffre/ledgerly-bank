# ADR-0020: Threads de plataforma no razão: as *virtual threads* medidas e deixadas de lado

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Desde a Fase 1 o razão atende cada pedido numa *virtual thread* (`spring.threads.virtual.enabled`).
A escolha foi pela promessa do mercado: uma *virtual thread* é do Java, e não do sistema
operacional, então custa pouco. Quando ela espera (o banco, a rede), sai da thread do sistema e
deixa outra rodar. Com isso o servidor atende milhares de pedidos simultâneos sem um *pool* de
centenas de threads do sistema, cada uma com cerca de 1 MB de pilha.

A alternativa é a **thread de plataforma**: uma thread do sistema operacional, em um *pool* fixo
(o Tomcat usa até 200).

A escolha nunca tinha sido medida. A Fase 3 tem o teste de carga (ADR-0021), então a pergunta é
direta: neste razão, sob carga, as *virtual threads* atendem mais pedidos?

## Como foi medido

- A mesma carga da ADR-0019: 64 clientes simultâneos por 30 s, com as contas quentes em 16
  sub-contas.
- Só muda `RAZAO_THREADS_VIRTUAIS` (`true` ou `false`). Todo o resto é igual: o código, o banco e o
  *pool* de 20 conexões com o banco (o padrão do Hikari).
- **3 rodadas alternadas** (virtual, plataforma, virtual, plataforma...), cada uma com o razão
  reiniciado e aquecido antes. Numa medição só, o ruído da máquina (uns 20%, ADR-0021) poderia
  inventar ou esconder a diferença.

## Números

Pedidos por segundo, com o *pool* de 20 conexões:

| Cenário | Modo | Rodada 1 | Rodada 2 | Rodada 3 | Média |
| --- | --- | --- | --- | --- | --- |
| Depósitos | *virtual threads* | 717 | 850 | 730 | **766** |
| Depósitos | threads de plataforma | 997 | 822 | 792 | **870** |
| Transferências | *virtual threads* | 867 | 994 | 1.024 | **962** |
| Transferências | threads de plataforma | 1.296 | 1.122 | 1.284 | **1.234** |

Zero erros nas 12 medições, e o razão íntegro depois de cada uma. A latência p99 ficou entre 104 e
188 ms nos dois modos, sem um vencedor claro.

**As threads de plataforma atenderam mais:** 14% nos depósitos e 28% nas transferências, na média.
Nos depósitos, a rodada 2 inverteu o resultado (850 × 822): a diferença ali é do tamanho do ruído.
Nas transferências, a plataforma ganhou nas 3 rodadas.

### E se o limite for o *pool* de conexões?

São 64 clientes ao mesmo tempo para 20 conexões com o banco. Nos dois modos, 44 pedidos ficam
esperando uma conexão. A hipótese era: o *pool* é o limite, e a diferença entre os modos está no
custo de esperar por ele. Para conferir, as mesmas medições, em 2 rodadas alternadas, com
`RAZAO_DB_POOL=64` (uma conexão por cliente):

| Cenário | Modo | *Pool* 20 (média) | *Pool* 64 (média) | Ganho com o *pool* |
| --- | --- | --- | --- | --- |
| Depósitos | *virtual threads* | 766 | 1.257 | +64% |
| Depósitos | threads de plataforma | 870 | **1.478** | +70% |
| Transferências | *virtual threads* | 962 | 1.827 | +90% |
| Transferências | threads de plataforma | 1.234 | **2.224** | +80% |

Zero erros e o razão íntegro nas 8 medições. Duas conclusões, uma confirmada e uma derrubada:

1. **O *pool* de conexões era o gargalo principal**, nos dois modos: a vazão quase dobrou.
2. **A hipótese para a diferença entre os modos estava errada.** Com 64 conexões, a plataforma
   continuou à frente (18% nos depósitos, 22% nas transferências). A causa não foi identificada.
   O candidato mais provável é o custo de agendar as *virtual threads* numa máquina com a CPU
   disputada pelo Gatling, pelo PostgreSQL e pelo razão ao mesmo tempo. Não foi medido, e por isso
   fica como hipótese, com a investigação descrita em "Consequências".

Um efeito colateral: com 64 conexões, o p99 dos depósitos subiu (de 144–188 ms para 283–322 ms),
enquanto o das transferências caiu (de 104–137 ms para 64–90 ms). Com mais transações abertas ao
mesmo tempo, mais delas disputam as 16 sub-contas do Caixa: o gargalo da ADR-0019 volta a aparecer,
agora mais adiante.

## Decisão

1. **O razão passa a usar threads de plataforma por padrão** (`RAZAO_THREADS_VIRTUAIS`, padrão
   `false`). Mediram mais vazão nas duas configurações de *pool*, e o motivo de existirem as
   *virtual threads* não se aplica aqui. Elas brilham quando há milhares de pedidos esperando ao
   mesmo tempo (rede, outros serviços). O razão espera uma coisa só: uma das suas conexões com o
   banco. O *pool* do Tomcat, de até 200 threads, já é maior que qualquer *pool* de conexões que
   faça sentido para um PostgreSQL. A chave continua, para medir de novo a cada versão do Java.
2. **O *pool* continua em 20 por padrão**, ajustável por `RAZAO_DB_POOL`. Com 64, o razão fez quase
   o dobro nesta máquina, mas o tamanho certo é do **banco**, e não do serviço. Cada cópia do razão
   abre o seu *pool*: quatro cópias com 64 são 256 conexões, mais que as 100 que o PostgreSQL aceita
   por padrão. O tamanho é decidido por ambiente, junto com o número de cópias (Fase 6, Kubernetes).
3. **Contas e extrato continuam com *virtual threads*.** Não foram medidos, e esta ADR não decide
   sem número.

## Alternativas consideradas

- **Não medir, e manter a escolha da Fase 1** — é o que muito projeto faz com as *virtual threads*:
  liga porque é moderno. A medição mostrou que, aqui, a promessa não se cumpre sozinha.
- **Um teste de carga de uma rodada só** — foi o primeiro resultado (694 × 489 pedidos/s nos
  depósitos, a favor da plataforma). Com um ruído de 20%, uma rodada só não prova nada. As 3
  rodadas alternadas mostraram que a plataforma ganha mesmo, mas por menos.
- **Manter as *virtual threads* e subir o *pool* para 64** — a configuração que mais ganha com o
  *pool* (1.827 transferências por segundo) ainda fica abaixo da plataforma com o mesmo *pool*
  (2.224). E o *pool* maior tem o custo no banco descrito acima.

## Consequências

- **Positivas:** a escolha de threads do razão agora tem um número ao lado, e o maior ganho
  disponível (o *pool*) ficou identificado e medido. A Fase 3 deixa um exemplo do que vale mais que
  qualquer recurso novo: medir antes de acreditar, e voltar atrás quando o número manda.
- **Negativas:**
  - a causa da diferença entre os modos não foi encontrada. O próximo passo é gravar as duas
    configurações com o *Java Flight Recorder* (JFR, o gravador de desempenho que vem no JDK) e
    comparar onde o tempo de CPU vai, e rodar o gerador de carga em outra máquina, para tirar a
    disputa de CPU da conta;
  - uma thread de plataforma presa (um pedido lento no banco) segura 1 MB de pilha e uma vaga do
    *pool* do Tomcat. Com até 200 threads e 20 conexões, a vaga de thread nunca acaba antes da de
    conexão, mas a conta precisa ser refeita se o razão passar a chamar outros serviços por HTTP;
  - os números são de uma máquina só, com tudo rodando junto (ADR-0021), e comparam os modos entre
    si: não são a capacidade do razão em produção.
