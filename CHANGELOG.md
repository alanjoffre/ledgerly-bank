# Histórico de versões

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

## [0.5.0] — 2026-10-30

### Adicionado — Fase 5 (IA aplicada)

- **Serviço antifraude** (`servicos/antifraude`, Quarkus 3.40, porta 8085): dá a cada Pix, **antes
  do débito**, uma nota de 0 a 1000, a decisão (aprovar, segurar ou recusar) e os motivos em
  português. A nota vem de um **modelo de regressão logística treinado em Python e executado na JVM
  em ONNX** (ONNX Runtime 1.31), mais duas regras: a rajada (5 Pix ou mais na hora) e a conta
  "laranja" (R$ 1.000,00 ou mais para conta aberta há menos de 2 dias, que nunca recebeu do
  pagador). Teste de paridade Python × Java com 50 casos; o modelo leva p50 ≈ 6,5 µs por Pix na JVM
  ([ADR-0032](docs/adr/0032-antifraude-regras-e-modelo-onnx.md)).
- **Treino do modelo** (`ml/`, fora do build Maven): 60 mil Pix sintéticos com 2% de fraude, semente
  fixa (treinar de novo dá o mesmo arquivo, byte a byte). A regressão logística ganhou das árvores
  com gradiente na precisão média (0,553 × 0,517); os limites de segurar (200) e recusar (900)
  saíram da tabela de limiares medida.
- **Pix retido antes do débito:** a situação `RETIDA`, a fila "Pix retidos" do backoffice e as
  decisões `POST /v1/transferencias/{id}/liberacao` e `/recusa`, só para analista e auditadas em
  `decisao_de_analise`. Antifraude fora do ar ou lento (800 ms): o Pix até R$ 500,00 segue, o maior
  fica retido (falha segura). **O cliente não vê a nota nem os motivos**: veria onde fica a linha
  ([ADR-0033](docs/adr/0033-pix-retido-antes-do-debito.md)).
- **Modelo de linguagem local, custo zero:** Ollama no Docker Compose (perfil `ia`, com a placa de
  vídeo), LangChain4j 1.23 sem extensão de framework, e `scripts/modelos.sh` para baixar os
  modelos. Nenhum teste do build precisa do Ollama: o modelo é falso e roteirizado
  ([ADR-0034](docs/adr/0034-modelo-de-linguagem-local-ollama.md)).
- **A explicação do Pix retido para a analista**, escrita pelo `gemma3:4b`
  (`GET /v1/avaliacoes/{id}/explicacao`). Uma trava confere que todo número do texto existe na
  avaliação; número inventado derruba o texto, e vale um texto fixo montado com os motivos
  ([ADR-0035](docs/adr/0035-explicacao-com-trava-de-numeros.md)).
- **Serviço assistente, "pergunte ao seu extrato"** (`servicos/assistente`, Spring Boot 4.1, porta
  8086): o cliente pergunta em português no internet banking, e o `qwen3:4b-instruct-2507` responde com três
  ferramentas **só de leitura** (contas, saldo e lançamentos, com o token do próprio cliente) e com
  os artigos de ajuda achados por busca em vetores (RAG, `bge-m3` no **pgvector**). Trava de entrada
  contra quem tenta trocar as instruções; trava de saída: valor em reais que não veio da ferramenta
  ou do artigo vira o texto de reserva ([ADR-0036](docs/adr/0036-assistente-rag-ferramentas-e-travas.md)).
- **Avaliação do assistente** (`avaliacao/`): 30 perguntas, cada uma com o fato que a resposta tem de
  trazer, rodadas contra o modelo de verdade, fora do CI; a régua e o conjunto têm teste no build
  ([ADR-0037](docs/adr/0037-avaliacao-do-assistente-fora-do-ci.md)). Primeira rodada (30/10):
  **27 de 30**, p50 de 1,35 s por pergunta, nenhum valor errado ou de outra conta. Antes dela, a
  avaliação achou dois defeitos que os testes do build não pegavam (eles usam um modelo falso):
  - o `qwen3:4b` "pensa" antes de responder e gastava os 300 *tokens* no raciocínio: resposta vazia.
    O assistente passou a usar o `qwen3:4b-instruct-2507-q4_K_M`, que responde direto;
  - o Ollama recusava toda pergunta ("properties must be an object"): o LangChain4j 1.23 manda a
    ferramenta sem parâmetro sem `properties`. O `ModeloComEsquemaCompleto` completa o esquema
    antes de o pedido sair (4 de 30 → 27 de 30).
- `web/e2e/capturas.mjs`: as capturas de tela dos posts, tiradas do sistema no ar pelo Playwright.
- Guia da [demonstração da Fase 5](docs/guias/demo-fase-5.md).
- Telas: "Pergunte ao seu extrato" no internet banking (e o aviso do Pix retido no envio); a fila e
  a tela do Pix retido no backoffice, com a explicação. Gateway: rotas do assistente (internet
  banking) e dos dois `GET` do antifraude (backoffice).
- `scripts/pix-retido.sh` cria um Pix retido de verdade, pela API, e dois testes de ponta a ponta
  do backoffice liberam e recusam um.
- Contas: o titular (`GET /v1/contas/{id}/titular`) traz `abertaEm`, a data de abertura da conta.
- CI: job `ml`, que treina o modelo de novo e confere que é o mesmo do repositório; o job de
  infraestrutura confere o banco isolado do antifraude e a extensão do pgvector. Dependabot também
  para as bibliotecas Python do treino.

### Corrigido

- **Os testes de ponta a ponta do Pix retido dependiam da hora:** de madrugada o modelo soma o sinal
  do horário e a nota sobe (500 → 882 com R$ 1.500,00; com R$ 2.000,00 passa de 900 e o antifraude
  recusa sozinho). O teste confere a nota por expressão, e a recusa usa R$ 1.500,00.
- **Os dois testes instáveis do CI da Fase 4**, cada um uma corrida de verdade, reproduzida antes do
  conserto ([ADR-0031](docs/adr/0031-as-duas-corridas-dos-testes-instaveis.md)):
  - **gateway:** o cliente HTTP do JDK 25 falhava com `NullPointerException` num corpo vazio
    mandado "em partes" (`Transfer-Encoding: chunked`). O gateway agora manda o corpo inteiro, com
    `Content-Length`, e o teste confere os cabeçalhos. Preso a 2 núcleos: 19 falhas em 30 rodadas
    antes, 0 depois.
  - **pix:** o agendador de retomada podia levar a Saga entre um passo e outro, e o pedido
    respondia **202** ("o razão não respondeu") para um Pix que ia ser concluído. Quem cria o Pix
    (ou decide a devolução) agora fica com a reserva até o fim. Teste novo `SagaEAgendadorIT`, com
    o agendador rodando sem pausa: 9 de 60 Pix errados antes, 0 depois.

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

[Não lançado]: https://github.com/alanjoffre/ledgerly-bank/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.5.0
[0.4.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.4.0
[0.3.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.3.0
[0.2.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.2.0
[0.1.0]: https://github.com/alanjoffre/ledgerly-bank/releases/tag/v0.1.0
