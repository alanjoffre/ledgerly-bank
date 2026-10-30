# ADR-0033: O antifraude decide antes do débito; Pix retido espera a analista; falha segura por valor

- **Status:** Aceita
- **Data:** 2026-10-29

## Contexto

O antifraude (ADR-0032) dá uma nota e uma decisão: aprovar, segurar ou recusar. Faltava decidir
**onde** essa pergunta entra na Saga do Pix (ADR-0015) e o que acontece em três casos:

- o antifraude **segura** o Pix: quem decide, e onde o dinheiro fica enquanto isso?
- o antifraude **não responde** (fora do ar, lento): o Pix trava, ou sai sem avaliação?
- o **cliente** consulta um Pix barrado: o que ele pode ver?

## Decisão

**Antes do dinheiro.** A avaliação é o primeiro passo da Saga: `CRIADA → APROVADA | RETIDA |
REJEITADA`, e só `APROVADA` segue para o débito. Pix retido ou recusado **não move dinheiro**: não
há o que estornar. Avaliar depois do débito obrigaria a estornar, e o cliente veria o dinheiro sair
e voltar. O banco também trava a regra: a restrição `situacao_coerente` (migração `V3`) não aceita um
Pix `APROVADA`, `RETIDA` ou `REJEITADA` com lançamento de envio.

**Pix retido espera uma pessoa.** `RETIDA` é uma situação nova, parada antes do débito:

- no backoffice, a fila `GET /v1/transferencias?situacao=RETIDA` (do mais antigo para o mais novo)
  mostra a nota, os motivos e a explicação (ADR-0035);
- a analista **libera** (`POST /{id}/liberacao`: a Saga segue na hora, débito e crédito) ou
  **recusa** (`POST /{id}/recusa`: fim, nenhum dinheiro saiu), com justificativa. A decisão vai
  para a mesma trilha de auditoria da devolução (ADR-0030);
- para o cliente, a API responde `202`, como a um Pix em andamento: ele ainda pode sair.

**Falha segura por valor.** O Pix chama o antifraude com tempo limite curto (300 ms para conectar,
800 ms para responder): o cliente está esperando, e o antifraude responde em milissegundos. Sem
resposta:

- **até R$ 500,00**, o Pix segue sem nota. O prejuízo possível é pequeno, e travar todo Pix porque
  um serviço caiu seria pior;
- **acima de R$ 500,00**, o Pix fica **retido**, com o motivo "Retido sem avaliação", e vai para a
  mesma fila da analista.

No mercado isso se chama *fail-safe* com *degradação controlada*: o sistema não para, mas o risco
alto vai para uma pessoa. O limite está em
[`TransferenciaPix.LIMITE_SEM_ANTIFRAUDE`](../../servicos/pix/src/main/java/io/github/alanjoffre/ledgerly/pix/dominio/TransferenciaPix.java).

**O cliente não vê a análise de risco.** A nota e os motivos ("15,0 vezes a média", "conta aberta
há 1 dia") são para a analista. Se o cliente lesse isso, o golpista também leria, e aprenderia a
ficar logo abaixo do limite. Por isso:

- a resposta da API leva a **nota de risco** só para a analista e para os serviços; para o cliente,
  ela vem vazia;
- o **motivo** de um Pix barrado pelo antifraude vira um texto genérico para o cliente: "Pix em
  análise de segurança antes de sair da sua conta. Nenhum dinheiro saiu." ou "Pix não autorizado
  pela análise de segurança. Nenhum dinheiro saiu da sua conta.";
- o motivo que **não** é de risco continua aparecendo como é: "saldo insuficiente" o cliente
  precisa ler, para resolver;
- a ajuda do assistente (ADR-0036) explica o Pix retido **sem** os limites, e a avaliação do
  assistente (ADR-0037) tem uma pergunta que falha se a resposta trouxer 200, 900 ou 500.

Os testes de integração `AntifraudeIT.clienteNaoVeAAnaliseDeRisco` e
`clienteVeMotivoQueNaoEDeRisco` travam as duas regras. A prova: sem o filtro, os dois falham.

## Alternativas consideradas

- **Avaliar depois do débito**, e estornar o que for suspeito. O dinheiro sai e volta, o extrato
  mostra os dois lançamentos, e o golpista pode ter tempo de sacar do outro lado. Avaliar antes é
  o que os bancos fazem com o Pix.
- **Sem antifraude, nenhum Pix sai** (*fail-closed*). Mais seguro no papel, mas qualquer queda do
  antifraude derruba o Pix inteiro. O limite de valor deixa passar o Pix pequeno e segura o grande.
- **Sem antifraude, todo Pix sai** (*fail-open*). Basta derrubar o antifraude para passar qualquer
  golpe.
- **Mostrar ao cliente o motivo da retenção.** Mais transparente, mas ensina o golpista. O cliente
  sabe o que precisa: o Pix está em análise e nenhum dinheiro saiu.

## Consequências

- **Positivas:** Pix suspeito não move dinheiro. A queda do antifraude não para o Pix e não abre a
  porta para valores altos. A decisão humana fica auditada.
- **Negativas:** um Pix retido espera uma pessoa. Fora do horário da analista, ele espera até o dia
  seguinte. Um banco real teria um prazo e uma equipe de plantão; aqui o prazo não existe.
- **O limite de R$ 500,00 é uma escolha de negócio**, não um número técnico: um banco real calibraria
  pelo prejuízo médio e pelo volume.
