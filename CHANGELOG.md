# Histórico de versões

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

## [0.4.0] — 2026-10-28

### Adicionado — Fase 4 (segurança e front-end)

- **Keycloak 26.8** no Docker Compose, com o realm `ledgerly` **como código**
  (`infra/docker/keycloak/ledgerly-realm.json`): papéis `cliente`, `analista` e `servico`; pessoas
  sintéticas; o vínculo login → cliente no claim `cliente_id`; token de 5 minutos com audiência
  `ledgerly-api` ([ADR-0024](docs/adr/0024-keycloak-e-realm-como-codigo.md)).
- **Todo serviço exige token** (OAuth2 *resource server*), inclusive de outro serviço: contas, pix e
  extrato chamam um ao outro com *client credentials*. O razão aceita só o papel `servico` e não tem
  rota no gateway ([ADR-0027](docs/adr/0027-token-de-servico-e-razao-sem-rota.md)). Biblioteca
  comum `bibliotecas/seguranca-spring` para os serviços Spring.
- **BOLA com 404:** o cliente só enxerga o que é dele; pedir a conta, o extrato ou o Pix de outra
  pessoa responde como se não existisse ([ADR-0026](docs/adr/0026-bola-com-404.md)).
- **Dois BFFs** (`servicos/gateway`, Spring Cloud Gateway Server WebMVC): internet banking na porta
  8080 e backoffice na 8090, cada um com o seu cookie de sessão. Login *authorization code* + PKCE,
  TokenRelay, CSRF de SPA, CSP, saída que encerra também a sessão do Keycloak; o token nunca chega ao
  navegador ([ADR-0025](docs/adr/0025-bff-um-por-front-end.md), [ADR-0028](docs/adr/0028-gateway-no-boot-4-0.md)).
- **Front-end React 19 + TypeScript** (`web/`, Vite): o **internet banking** (contas, extrato,
  chaves Pix e envio de Pix com "confira o destinatário") e o **backoffice de fraude** (fila de Pix
  em análise, decisão de devolução, consulta de conta). Dinheiro como texto do começo ao fim
  ([ADR-0029](docs/adr/0029-react-vite-vitest-playwright.md)).
- **Devolução de Pix em análise:** `POST /v1/transferencias/{id}/devolucao`, só para analista; o
  dinheiro volta para outra conta ATIVA do mesmo titular quando a que pagou foi encerrada, com
  auditoria de cada decisão em `decisao_de_analise`. O evento `PixEstornado` ganhou o campo opcional
  `contaDevolucaoId` ([ADR-0030](docs/adr/0030-devolucao-de-pix-em-analise.md)).
- **Testes de ponta a ponta com o Playwright** (`web/e2e`), no Chromium, contra o sistema inteiro
  no ar: login no Keycloak, Pix até o extrato, BOLA pelo BFF, saída, fila de análise e devolução.
  Testes de unidade das telas com Vitest + Testing Library.
- Scripts: `scripts/semear.sh` (pessoas, contas, saldo e chaves, idempotente),
  `scripts/pix-em-analise.sh` (cria um Pix em análise de verdade) e `scripts/lib/token.sh` (o token
  de serviço para os scripts). `desempenho/carga/medir-gateway.sh` mede o custo do BFF.
- CI: job `web` (tipos, testes e build das telas) e a conferência do realm do Keycloak no job de
  infraestrutura.

### Mudado

- As demonstrações das Fases 1 a 3, a medição de subida e o teste de carga passaram a mandar o token
  de serviço: sem ele, os serviços respondem 401.
- O `maven-jar-plugin` tem versão fixa no `pom.xml` raiz (o build avisava).

### Corrigido

Cinco defeitos que só os testes de ponta a ponta acharam — cada um provado antes por um teste que
falhava, e coberto agora também por teste do gateway, do pix ou da tela, que rodam no build:

- **O BFF transformava 403 em 200.** Um pedido sem permissão era encaminhado para `/error`, e o
  *fallback* da SPA respondia ali com a tela (`index.html`). Agora 401 e 403 em `/api/**` saem em
  JSON (*problem details*) direto do filtro de segurança, e `/error` não serve a tela.
- **O Pix pelo internet banking falhava.** A tela mandava a chave **mascarada** que a consulta
  devolve (`b***@exemplo.test`), e não a digitada.
- **Depois do login, a pessoa caía num JSON.** O BFF guardava o último pedido de API sem sessão
  (`/api/eu`) e voltava para ele; agora nenhum pedido fica guardado e o login volta para a tela.
- **A mensagem do Pix não aparecia no extrato** (desde a Fase 2). A Saga mandava ao razão o texto
  fixo "Pix para <nome>", e o "Aluguel" de quem pagou se perdia. Agora o débito e o crédito levam a
  mensagem ([ADR-0029](docs/adr/0029-react-vite-vitest-playwright.md)).
- **"Minhas contas" dizia "Saldo indisponível agora" em toda conta.** A lista de contas do cliente
  vem sem saldo, mas o falso do teste da tela trazia. Agora a tela busca o saldo de cada conta.

## [0.3.0] — 2026-10-28

### Adicionado — Fase 3 (concorrência e desempenho)

- **Teste de carga** com o Gatling (`desempenho/carga`): depósitos, envios de Pix e transferências,
  64 clientes simultâneos, com a integridade do razão conferida depois de cada rodada. Os resumos
  ficam em `desempenho/resultados/carga/` ([ADR-0021](docs/adr/0021-teste-de-carga-com-gatling.md)).
- **Contas quentes em sub-contas:** o Caixa e a Liquidação Pix viraram 16 sub-contas cada, e cada
  lançamento trava só uma. Depósitos e envios de Pix quase 5× mais rápidos, p99 de ~1 s para ~0,25 s
  (`RAZAO_SUBCONTAS`, [ADR-0019](docs/adr/0019-contas-quentes-em-subcontas.md)).
- `GET /v1/integridade` mostra o saldo de cada conta interna (a soma das sub-contas) e quantas
  sub-contas estão negativas.
- *Virtual threads* no razão medidas contra threads de plataforma, em rodadas alternadas, e
  configuráveis (`RAZAO_THREADS_VIRTUAIS`, [ADR-0020](docs/adr/0020-virtual-threads-medidas-no-razao.md)).
- **Conciliação em lote** Pix × razão (`lotes/conciliacao`): *merge join* por faixas de ids, em
  paralelo com *Structured Concurrency* e *Scoped Values*, sobre milhões de registros sintéticos
  ([ADR-0022](docs/adr/0022-conciliacao-em-lote-com-structured-concurrency.md)).
- **Micro-benchmarks** com o JMH (`desempenho/jmh`): `BigDecimal` × `long`; `synchronized`,
  `ReentrantLock`, `AtomicLong` e `LongAdder` sob disputa; *virtual threads* × pool de threads
  ([ADR-0023](docs/adr/0023-micro-benchmarks-com-jmh.md)).

### Mudado

- O razão passou a usar **threads de plataforma** por padrão, e não *virtual threads*: medidas sob
  carga, elas atenderam de 14% a 28% mais pedidos. O limite do razão é o pool de conexões com o banco
  (com 64 conexões em vez de 20, a vazão quase dobrou nos dois modos) ([ADR-0020](docs/adr/0020-virtual-threads-medidas-no-razao.md)).
- `POST /v1/pix/recebimentos` e `/estornos` no razão exigem `lancamentoEnvioId`, o lançamento do
  envio de onde o dinheiro sai; o serviço de Pix já manda. Novo erro `422 envio-pix-invalido`.
- A demonstração da Fase 2 compara a Liquidação Pix com o saldo do início, e não com zero.

### Corrigido

- **O razão aceitava duas saídas do mesmo envio de Pix.** Receber e também estornar o mesmo envio
  passava, se a Liquidação Pix tivesse dinheiro de outros Pix: o dinheiro aparecia do nada. Só a
  Saga impedia. Agora há no máximo uma saída por envio, conferida na aplicação e por um índice
  único no banco (ADR-0019).

- **Pix para conta recém-aberta era estornado.** O razão só conhece a conta depois de consumir o
  `ContaAberta`; nesse intervalo, o `404 conta-nao-encontrada` no crédito virava recusa e estorno.
  Agora, por até 5 minutos desde a criação do Pix, ele conta como "sem resposta" e o crédito é
  repetido (adendo na ADR-0015).
- Documentação: o README e a ADR-0009 diziam "20 lançamentos" em 100 cliques sem a trava; refeito o
  experimento, sai 1 lançamento e 19 erros falsos de "chave já usada" (o `UNIQUE` segura). Também:
  a tabela de competências do README marca o que é planejado e em que fase; o CHECK de saldo na
  ADR-0009; os códigos "sem resposta" na ADR-0015; o README do pix; o `GET /titular` no README de
  contas; o pix na visão geral roda na JVM; o `eventoId` em eventos.md; MongoDB 9 na Fase 0.

## [0.2.0] — 2026-10-27

### Adicionado — Fase 2 (Pix e eventos)

- Serviço **pix** (Quarkus): chaves CPF, e-mail, telefone e aleatória; "confira o destinatário"
  com a chave mascarada; QR Code estático no padrão BR Code (EMV + CRC16), em texto e SVG.
- Transferência Pix como **Saga orquestrada**, com a conta interna Liquidação Pix, estorno quando o
  crédito é recusado, nova tentativa com espera exponencial quando o razão não responde, reserva
  por tempo e retomada automática das transferências paradas.
- Trava rápida no **Redis** contra pedido repetido, com o `UNIQUE` do banco como garantia; o Pix
  continua correto com o Redis fora do ar.
- **Outbox** no razão, no serviço de contas e no Pix, com publicador para o Kafka (`acks=all`,
  produtor idempotente) e limpeza das linhas publicadas.
- O serviço de contas avisa o razão por **evento** (abrir, bloquear, desbloquear); o razão consome
  com *dead letter topic*. Novas rotas `GET /v1/contas/{id}/titular` e
  `POST /v1/contas/{id}/titularidade`.
- Movimentos do Pix no razão: `POST /v1/pix/envios`, `/recebimentos` e `/estornos`.
- Serviço **extrato** (Spring Boot + MongoDB): extrato e saldo montados pelos eventos, em qualquer
  ordem, com script de reconstrução a partir do tópico.
- Tópicos do Kafka criados pelo Compose (a criação automática fica desligada); usuário próprio do
  extrato no MongoDB.
- Scripts: `servicos.sh` (sobe e derruba os quatro serviços), `demo-fase-2.sh`, `medir-subida.sh`.
- Contrato dos eventos; ADRs 0013–0018; guia de demonstração da Fase 2.

### Alterado — Fase 2

- Abrir e bloquear conta não dependem mais do razão no ar: o razão recebe pelo evento (a ADR-0011
  foi substituída pela ADR-0014). O encerramento continua síncrono, porque depende do saldo.

### Corrigido — Fase 2

- O motivo de um estorno saía com o detalhe técnico do razão (o identificador da conta); agora é
  uma frase para o cliente ("a conta de destino está encerrada"). Achado na demonstração.

## [0.1.0] — 2026-10-26

### Adicionado — Fase 1 (razão contábil e contas)

- Serviço **razao** (Spring Boot + JdbcClient): partidas dobradas, contas devedoras e credoras,
  depósito, saque, transferência, extrato e conferência de integridade.
- Serviço **contas** (Spring Boot + JPA): clientes com CPF validado e mascarado, conta como máquina
  de estados, saldo consultado no razão.
- Trava pessimista ordenada no razão e otimista nas contas, com testes de concorrência e prova
  reversa (sem a trava, os testes acusam gasto duplo e atualização perdida).
- Idempotência por `Idempotency-Key`, resistente a pedidos simultâneos.
- Regras garantidas também pelo PostgreSQL: `CHECK` de saldo, gatilhos de partidas dobradas e de
  imutabilidade, `UNIQUE` da chave.
- Erros em Problem Details (RFC 9457), em português.
- Script e guia de demonstração de ponta a ponta.
- ADRs 0008–0012.

### Corrigido — Fase 1

- O horário do lançamento passou a ter a precisão do PostgreSQL (microssegundos): antes, a resposta
  de um pedido repetido saía com o horário diferente da original. Achado pelo teste de integração.

### Adicionado — Fase 0 (fundação)

- Monorepo Maven multimódulo com Java 25 e Maven Wrapper.
- Núcleo compartilhado com o *value object* `Dinheiro`: `BigDecimal` na escala da moeda,
  arredondamento bancário, rateio sem perda de centavo e proteção contra mistura de moedas.
- Qualidade no build: avisos do compilador como erro, cobertura mínima de 90% (JaCoCo),
  teste de arquitetura (ArchUnit), teste de propriedade.
- Infraestrutura local (Docker Compose): PostgreSQL 18 com um banco por serviço, MongoDB 9,
  Redis 8 e Kafka 4 em modo KRaft.
- CI no GitHub Actions (build, testes e validação da infraestrutura) e Dependabot.
- Documentação: arquitetura C4, ADRs 0001–0007, roteiro de fases, glossário, guia de ambiente.

[Não lançado]: https://github.com/alanjoffre/ledgerly-bank/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.4.0
[0.3.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.3.0
[0.2.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.2.0
[0.1.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.1.0
