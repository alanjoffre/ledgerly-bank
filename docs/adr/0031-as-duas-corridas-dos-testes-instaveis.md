# ADR-0031: As duas corridas por trás dos testes instáveis da Fase 4

- **Status:** Aceita
- **Data:** 2026-10-29

## Contexto

Na publicação da Fase 4 (v0.4.0), o CI falhou duas vezes em testes que passavam na máquina local e
passavam de novo ao repetir o CI — os chamados **testes instáveis** (em inglês, *flaky tests*):

- **gateway**, `InternetBankingTest.encaminha`: o pedido ao serviço falhava com
  `NullPointerException` ("bodySubscriber is null"), de dentro do cliente HTTP do Java;
- **pix**, `DevolucaoIT.doisAnalistasAoMesmoTempo`: o Pix respondeu **202** ("o razão não
  respondeu, ele segue sozinho") quando devia responder **201** ("concluído").

Teste instável não se resolve com "repetir até passar" (o *retry* automático) nem desligando o
teste: as duas falhas eram **corridas** (em inglês, *race conditions*) — dois caminhos rodando ao
mesmo tempo, e o resultado depende de quem chega primeiro. Com pouca CPU, como no runner do CI
(2 núcleos), a ordem "rara" aparece. As duas foram reproduzidas antes de qualquer conserto.

### 1. O corpo vazio "em partes" no cliente HTTP do JDK

O gateway repassa cada pedido ao serviço com o cliente HTTP do próprio Java
(`java.net.http.HttpClient`), montado pelo Spring. Quando não sabe o tamanho do corpo, o Spring o
manda **em partes** (`Transfer-Encoding: chunked`) — e, num GET, o corpo em partes é vazio. O
publicador do Spring avisa "acabou" (`onComplete`) de outra thread logo depois de começar, o que as
regras de *Reactive Streams* permitem. No JDK 25, se esse aviso chega antes de o cliente terminar
de se preparar (`Http1Exchange.sendBodyAsync` grava o `bodySubscriber` só depois de se inscrever),
o cliente usa um campo ainda nulo e o pedido falha.

**Provas:**

- um programa só com o JDK, sem Spring, reproduz a falha: **1 em 20.000** pedidos com 16 núcleos,
  **62 em 20.000** preso a 1 núcleo (`taskset -c 0`);
- o teste original, rodado 30 vezes, cada vez numa JVM nova, preso a 2 núcleos: **19 falhas em 30**;
- o serviço falso do teste passou a anotar os cabeçalhos: todo GET chegava com
  `Transfer-Encoding: chunked`.

### 2. O agendador levando a Saga do Pix no meio do pedido

A Saga do Pix (ADR-0015) reserva a transferência por 30 s a cada passo, e um agendador retoma, a
cada segundo, as Sagas paradas. Mas a reserva era **solta** ao gravar a transferência nova e ao
fim de cada passo, e o pedido HTTP tentava reservar de novo para o passo seguinte. Se o agendador
passasse nesse instante, ele levava a Saga; o pedido não conseguia reservar, achava que o razão
não tinha respondido e devolvia **202** com a transferência ainda em `CRIADA` ou `DEBITADA` — para
um Pix que terminaria `CONCLUIDA` milissegundos depois. Na devolução de um Pix em análise, o mesmo:
`200 ESTORNANDO` em vez de `200 ESTORNADA`.

Isso **não era um defeito do teste**: um cliente de verdade receberia "o razão não respondeu" sem o
razão ter falhado. Com o agendador rodando sem pausa, num laço (`SagaEAgendadorIT`), **9 de 60** Pix
responderam 202, e na devolução 4 Pix e 1 devolução saíram errados.

## Decisão

- **Gateway: o corpo do pedido vai inteiro, nunca em partes**
  ([`ClienteHttp`](../../servicos/gateway/src/main/java/io/github/alanjoffre/ledgerly/gateway/ClienteHttp.java)).
  A fábrica de pedidos do Spring Boot é embrulhada: o corpo é lido para a memória antes de sair.
  Vazio, o pedido vai sem corpo; com bytes, vai com `Content-Length`. A **resposta** continua
  passando direto, sem ir para a memória. O teste confere os cabeçalhos que o serviço recebe: sem
  `Transfer-Encoding`, e `Content-Length` só quando há corpo.
- **Pix: quem começa a Saga fica com ela até o fim**
  ([`RegistroDaSaga`](../../servicos/pix/src/main/java/io/github/alanjoffre/ledgerly/pix/aplicacao/RegistroDaSaga.java),
  [`OrquestradorDaSaga`](../../servicos/pix/src/main/java/io/github/alanjoffre/ledgerly/pix/aplicacao/OrquestradorDaSaga.java)).
  A transferência nova já é gravada **reservada** para o pedido que a criou, e a decisão de
  devolução já sai reservada para quem decidiu. A cada passo, a reserva é **renovada** se o
  próximo é para agora; é solta só quando a Saga termina ou um passo fica sem resposta (aí sim é
  do agendador). Se o serviço morrer no meio, nada muda: a reserva vence em 30 s e outra execução
  assume, como antes.

## Alternativas consideradas

- **Repetir o teste que falha** (`rerunFailingTestsCount` do Surefire) — esconderia as duas
  corridas; a do Pix chegaria ao cliente.
- **Gateway: trocar o cliente do JDK** pelo Reactor Netty ou pelo Apache HttpClient — mais uma
  dependência (e outro jeito de configurar tempo limite e conexões) para contornar um caso só: o
  corpo em partes. Fica a nota para rever quando o JDK corrigir a corrida.
- **Gateway: tratar só o GET sem corpo** — o defeito é do corpo em partes, e não do método; com o
  corpo inteiro, nenhum método passa por ele.
- **Pix: travar a linha no banco durante a Saga toda** (`SELECT ... FOR UPDATE`) — prenderia uma
  conexão enquanto se espera o razão: o motivo de a ADR-0015 ter escolhido a reserva por tempo.
- **Pix: o pedido esperar o agendador terminar** — resposta mais lenta e mais um ponto de espera
  sem necessidade: quem já está com a Saga na mão pode levá-la até o fim.

## Consequências

- **Positivas:** os dois testes deixaram de falhar — gateway **0 falhas em 30** rodadas presas a 2
  núcleos (antes, 19); Pix **0 de 60** com o agendador sem pausa (antes, 9). E um defeito de
  produto saiu antes de um cliente vê-lo: o 202 falso do Pix.
- **Negativas:** o gateway guarda em memória o corpo de cada pedido antes de repassá-lo — aqui são
  JSON pequenos (um Pix, um QR Code lido), mas um envio de arquivo grande pediria outro caminho. O
  contorno do JDK fica no código até a correção do próprio JDK.
