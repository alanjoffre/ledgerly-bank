# ADR-0014: O serviço de contas avisa o razão por evento; só o encerramento continua síncrono

- **Status:** Aceita — substitui a [ADR-0011](0011-contas-chama-razao-dentro-da-transacao.md)
- **Data:** 2026-10-27

## Contexto

Na Fase 1, abrir, bloquear, desbloquear e encerrar uma conta chamavam o razão por HTTP **dentro
da transação local** ([ADR-0011](0011-contas-chama-razao-dentro-da-transacao.md)). Isso deixava
dois problemas, registrados lá como provisórios:

- **acoplamento de disponibilidade:** com o razão fora do ar, não se abria nem bloqueava conta
  (`503`), embora nenhuma dessas operações precise de uma resposta do razão — elas só o avisam;
- **a janela "razão adiantado":** o razão aceitava e o COMMIT local falhava, deixando os dois lados
  diferentes até alguém repetir a operação.

A Fase 2 traz o Kafka e o Outbox ([ADR-0013](0013-outbox-para-publicar-eventos.md)).

## Decisão

Separar o que **avisa** do que **pergunta**:

| Operação | Precisa de resposta do razão? | Como fica |
|---|---|---|
| Abrir | Não | Evento `ContaAberta` |
| Bloquear / desbloquear | Não | Eventos `ContaBloqueada` / `ContaDesbloqueada` |
| Encerrar | **Sim** — o saldo é zero? | HTTP síncrono, dentro da transação, e o evento `ContaEncerrada` |
| Consultar | Sim — qual o saldo? | HTTP síncrono |

Os eventos são gravados na tabela `outbox` **na mesma transação** da mudança na conta e publicados
no tópico `contas.eventos.v1` com a chave `contaId`
([contrato](../arquitetura/eventos.md#contaseventosv1)). Mesma chave, mesma partição: os eventos de
uma conta chegam ao razão na ordem em que aconteceram.

O evento só é gravado **depois** de a trava otimista aceitar a mudança: dos 50 bloqueios simultâneos
do teste de concorrência, sai exatamente um `ContaBloqueada`.

### Por que o encerramento continua síncrono

Encerrar com saldo é proibido, e o saldo só existe no razão. Por evento, o serviço de contas
encerraria a conta, publicaria `ContaEncerrada`, e o razão — se houvesse saldo — teria que recusar
**depois**, quando o cliente já recebeu "encerrada". Para desfazer seria preciso uma compensação
(reabrir a conta, avisar o cliente): uma Saga inteira para uma operação rara. A pergunta síncrona
resolve na hora: o razão recusa, a transação local é desfeita, nada muda.

O que sobra da janela da ADR-0011 fica **só no encerramento**, e é inofensivo: se o razão encerrar e
o COMMIT local falhar, a conta fica encerrada no razão (que recusa qualquer movimento) e ativa aqui;
repetir o encerramento acerta os dois (a chamada é idempotente).

### A nova janela: conta recém-aberta

Entre o COMMIT da abertura e o razão consumir o `ContaAberta`, o razão ainda não conhece a conta.
Nessa janela:

- **consultar** mostra saldo `0.00`. É exato, não um palpite: o razão só aceita movimento numa conta
  que ele conhece — antes do evento, nenhum depósito foi possível;
- **encerrar** funciona: o razão responde `404 conta-nao-encontrada`, que este serviço lê como
  "saldo zero por construção"; o `ContaEncerrada` sai depois do `ContaAberta` na mesma partição, e
  o razão abre e encerra a conta nessa ordem. Só o `404` com esse `type` vale: outro 404 (um
  endereço errado na configuração) é erro, e nunca vira "saldo zero";
- **depositar** direto no razão responde `404` até o evento chegar.

É a **consistência eventual** (*eventual consistency*) trocando de lugar com a disponibilidade: a
conta abre mesmo com o razão fora, e o preço é essa janela: normalmente o intervalo do publicador
(100 ms) mais o tempo de o razão consumir; sem limite, se o razão ou o Kafka estiverem fora.

## Alternativas consideradas

- **Tudo por evento, inclusive o encerramento** — exigiria encerrar "pendente", esperar a resposta
  do razão por outro evento e reabrir em caso de saldo: uma Saga para uma operação rara, e uma
  situação a mais na máquina de estados da conta.
- **Manter tudo síncrono (ADR-0011)** — o serviço de contas continuaria parado sempre que o razão
  parasse, por operações que não precisam de resposta.
- **Chamar o razão por HTTP depois do COMMIT, com nova tentativa** — a tentativa morre se o serviço
  cair entre o COMMIT e a chamada: é a escrita dupla que o Outbox resolve.

## Consequências

- **Positivas:**
  - abrir, bloquear e desbloquear funcionam com o razão fora do ar (testado), e com o Kafka fora
    do ar também (o evento espera no Outbox — testado com o contêiner do Kafka pausado);
  - a requisição de abertura não espera mais o razão (até 5 s antes);
  - a janela "razão adiantado" deixa de existir para três das quatro operações.
- **Negativas:**
  - **consistência eventual:** a conta pode existir aqui e ainda não no razão, por um instante (ou
    pelo tempo que o razão ou o Kafka ficarem fora);
  - **um bloqueio não é instantâneo no razão:** entre o `POST /bloqueio` e o razão consumir o
    evento, um saque ainda pode passar. Para um bloqueio por fraude, isso importa; a Fase 5
    (antifraude) mede essa janela e decide se o bloqueio por fraude precisa de um caminho síncrono;
  - o serviço de contas passa a depender do Kafka para que o razão fique em dia — e o publicador do
    Outbox vira mais uma peça para monitorar (eventos pendentes há muito tempo = alerta; **não foi feito na Fase 6**: a [ADR-0042](0042-opentelemetry-e-otel-lgtm.md) não tem alertas).
