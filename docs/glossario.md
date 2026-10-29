# Glossário

Termos de banco e de tecnologia usados no projeto, em ordem alfabética. Termos em inglês trazem a
tradução e o significado.

## Banco e finanças

| Termo | Significado |
| --- | --- |
| **Arredondamento bancário** | Arredondar "meio para o par": 2,345 → 2,34; 2,355 → 2,36. Evita que arredondamentos sucessivos puxem tudo para cima. Em inglês, *banker's rounding* ou `HALF_EVEN`. |
| **BR Code** | Padrão do Banco Central para o QR Code do Pix: os dados (chave, valor, nome, cidade) em campos no formato EMV, terminando num código de conferência (CRC). O "copia e cola" é esse mesmo texto. |
| **Chave Pix** | Apelido de uma conta para receber Pix: CPF, e-mail, celular ou chave aleatória. |
| **Conciliação** | Conferir que dois registros do mesmo dinheiro batem (por exemplo, o razão interno e o extrato do sistema de pagamentos). Diferença = investigar. |
| **Conciliação em lote** | A conciliação feita de uma vez sobre todos os registros do dia, depois do fechamento, e não a cada operação. Aqui, Pix × razão ([ADR-0022](adr/0022-conciliacao-em-lote-com-structured-concurrency.md)). |
| **Conta de liquidação** | Conta interna do banco onde o dinheiro de uma operação em andamento espera entre sair de um cliente e chegar a outro. Aqui, a Liquidação Pix: com todo Pix terminado, ela fica zerada. |
| **Conta quente** | Conta que quase toda operação precisa travar (aqui, o Caixa e a Liquidação Pix), virando gargalo. Em inglês, *hot account*. A solução da Fase 3 é dividi-la em sub-contas ([ADR-0019](adr/0019-contas-quentes-em-subcontas.md)). |
| **Controle preventivo e detectivo** | O **preventivo** impede o erro de acontecer (o `CHECK` que recusa saldo negativo). O **detectivo** acha o erro depois (a conferência de integridade). Auditorias pedem os dois. |
| **Estorno** | Desfazer uma transação com uma nova transação no sentido contrário. No razão, nada é apagado. |
| **Gasto duplo** | Gastar o mesmo saldo duas vezes, quando duas operações simultâneas leem o saldo antes de qualquer uma debitar. Em inglês, *double spending*. |
| **KYC** | *Know Your Customer*, "conheça seu cliente": verificar a identidade de quem abre conta. |
| **Lançamento** | Um registro no razão: débito ou crédito de um valor numa conta, numa data. |
| **Natureza da conta** | Diz que lado aumenta o saldo. **Devedora** (ativo, como o caixa): débito aumenta. **Credora** (passivo, como o dinheiro do cliente no banco): crédito aumenta. |
| **Partidas dobradas** | Princípio contábil: todo movimento tem um débito e um crédito de mesmo valor. A soma de todos os lançamentos é sempre zero — se não for, há erro. Em inglês, *double-entry bookkeeping*. |
| **Pix** | Pagamento instantâneo brasileiro, criado pelo Banco Central, que funciona 24 horas. |
| **Rateio** | Dividir um valor em partes. Exige cuidado para a soma das partes fechar com o total. |
| **Razão (contábil)** | O livro onde ficam todos os lançamentos. O saldo de uma conta é a soma dos seus lançamentos. Em inglês, *ledger* — daí o nome Ledgerly. |
| **SPI** | Sistema de Pagamentos Instantâneos do Banco Central, que liquida o Pix entre bancos. Aqui é simulado. |
| **Sub-conta** | Uma das partes em que uma conta quente é dividida. Cada lançamento trava uma só; o saldo da conta é a soma das sub-contas. |
| **Titularidade** | Quem é o dono da conta. A chave Pix CPF só pode ser registrada pelo titular daquele CPF. |

## Tecnologia e arquitetura

| Termo | Significado |
| --- | --- |
| **acks=all** | Configuração do produtor Kafka: a mensagem só é confirmada quando todas as cópias do tópico a gravaram. Mais lento, mas uma queda do Kafka não perde o que já foi confirmado. |
| **ADR** | *Architecture Decision Record*: documento curto que registra uma decisão de arquitetura e seu motivo. Ver [adr/](adr/README.md). |
| **Agregado** | No DDD, um grupo de objetos tratado como unidade de consistência (por exemplo, a Conta e seus limites). Em inglês, *aggregate*. |
| **Aquecimento (da JVM)** | Os primeiros segundos de um programa Java são mais lentos: o JIT ainda está compilando o código mais usado para código de máquina. Medição séria descarta esse começo. Em inglês, *warm-up*. |
| **ArchUnit** | Biblioteca que testa a estrutura do código ("o domínio não pode importar Spring"). |
| **Arquitetura hexagonal** | Também chamada *Ports & Adapters*: as regras de negócio ficam no centro, sem depender de framework; banco, HTTP e Kafka são "adaptadores" nas bordas, trocáveis. |
| **At-least-once** | "Pelo menos uma vez": garantia de entrega em que nenhuma mensagem se perde, mas uma pode chegar repetida. Por isso todo consumidor precisa ser idempotente. |
| **Atualização perdida** | Duas transações leem o mesmo valor, as duas gravam, e a segunda apaga a mudança da primeira. Em inglês, *lost update*. |
| **Audiência (do token)** | O campo `aud` do token: para qual sistema ele foi emitido. Aqui, `ledgerly-api`. Um serviço recusa token feito para outro sistema, mesmo que venha do mesmo Keycloak. |
| **BFF** | *Backend for Frontend*: um servidor feito para uma tela só, que fica entre o navegador e os serviços. Aqui, ele faz o login, guarda o token na sessão e repassa os pedidos; o navegador só tem um cookie ([ADR-0025](adr/0025-bff-um-por-front-end.md)). |
| **BOLA** | *Broken Object Level Authorization*: a API confere **quem** você é, mas não **se o objeto é seu** — trocar o id na URL mostra a conta de outra pessoa. É a falha nº 1 do OWASP API Security Top 10. Aqui, o pedido de outro dono responde 404 ([ADR-0026](adr/0026-bola-com-404.md)). |
| **BOM** | *Bill of Materials*: lista de versões de bibliotecas que combinam entre si, importada pelo Maven. |
| **C4** | Modelo de diagramas de arquitetura em quatro níveis de zoom: contexto, contêineres, componentes, código. |
| **CAS** | *Compare-and-swap*, "compare e troque": instrução da CPU que grava um valor só se ele ainda for o esperado. Base das classes atômicas do Java (`AtomicLong`), sem trava. Sob muita disputa, as tentativas falham e se repetem. |
| **CI** | *Continuous Integration*, integração contínua: a cada envio de código, um servidor compila e testa tudo automaticamente. |
| **Claim** | Cada informação dentro de um token JWT: quem é (`sub`), quem emitiu (`iss`), para quem (`aud`), até quando (`exp`), os papéis. Aqui também o `cliente_id`, o vínculo do login com o cliente do banco. |
| **Client credentials** | Fluxo do OAuth2 sem pessoa: um **serviço** se identifica com o próprio id e segredo e recebe um token em nome dele. É como contas, pix e extrato se chamam ([ADR-0027](adr/0027-token-de-servico-e-razao-sem-rota.md)). |
| **Compensação** | Na Saga, o passo que desfaz o efeito de um passo anterior quando um seguinte falha (aqui, o estorno do Pix). Não apaga nada: é um movimento novo, no sentido contrário. |
| **Condição de corrida** | Defeito em que dois caminhos rodam ao mesmo tempo e o resultado depende de quem chega primeiro. Costuma aparecer só com a máquina ocupada ou com pouca CPU, como no CI. Em inglês, *race condition*. Aqui, as duas da [ADR-0031](adr/0031-as-duas-corridas-dos-testes-instaveis.md). |
| **Consistência eventual** | Os dados de serviços diferentes podem ficar diferentes por um instante, mas convergem sozinhos. Exemplo: o extrato mostra o Pix milissegundos depois do razão. |
| **Consumidor, grupo e offset** | Quem lê um tópico do Kafka é um *consumidor*; consumidores com o mesmo nome de *grupo* dividem o trabalho; o *offset* é a posição até onde o grupo já leu. A diferença entre o fim do tópico e o offset é o atraso (*lag*). |
| **Contexto delimitado** | No DDD, a fronteira dentro da qual um modelo vale. Cada serviço é um: o "Conta" do razão não é o "Conta" do cadastro. Em inglês, *bounded context*. |
| **Corpo em partes** | Pedido HTTP que manda o corpo em pedaços, sem dizer o tamanho antes (`Transfer-Encoding: chunked`); o contrário é dizer o tamanho no `Content-Length`. O gateway manda sempre o corpo inteiro ([ADR-0031](adr/0031-as-duas-corridas-dos-testes-instaveis.md)). |
| **CQRS** | *Command Query Responsibility Segregation*: separar o modelo que **grava** do modelo que **lê**, cada um otimizado para a sua tarefa. |
| **CRC** | *Cyclic Redundancy Check*: número calculado a partir de um texto para detectar alteração. Um caractere trocado no QR Code muda o CRC, e o QR Code é recusado. |
| **CSP** | *Content Security Policy*: cabeçalho que diz ao navegador de onde a página pode carregar script, estilo e imagem. Com `default-src 'self'`, um script injetado de outro lugar não roda. |
| **CSRF** | *Cross-Site Request Forgery*: um site malicioso faz o navegador da vítima mandar um pedido ao banco, e o cookie vai junto. A defesa aqui: um token num cookie legível (`XSRF-TOKEN`) que a tela copia num cabeçalho — o site de fora não consegue ler o cookie, então não forja o pedido. |
| **DDD** | *Domain-Driven Design*: projetar o software a partir do domínio de negócio e da sua linguagem. |
| **Deadlock** | Impasse: a transação 1 espera um recurso da 2, e a 2 espera um da 1. Nenhuma anda; o banco aborta uma delas. |
| **Decimal128** | Tipo numérico decimal do MongoDB, sem a perda de precisão do `double`. O extrato guarda dinheiro nele. |
| **Defesa em profundidade** | Proteger a mesma regra em mais de uma camada (aplicação e banco), para que um erro numa não vire dano. |
| **Dev Services** | Recurso do Quarkus que, nos testes, sobe sozinho os contêineres de banco, Kafka e Redis — usa o Testcontainers por baixo, sem precisar configurar nada. |
| **DLT** | *Dead letter topic*, tópico de mensagens mortas: para onde vai a mensagem que o consumidor não consegue processar nem depois das novas tentativas, para não travar a fila atrás dela. |
| **Dublê de teste** | Peça falsa que substitui uma dependência real no teste. Um *fake* (como o `RazaoFalso`) tem comportamento simples de verdade; um *mock* só registra chamadas. |
| **Emissor (do token)** | O campo `iss` do token: quem o emitiu (o endereço do realm no Keycloak). O serviço só aceita token do emissor configurado. |
| **Escrita dupla** | Gravar no banco e publicar no Kafka como duas operações separadas. Se a segunda falhar, o dado e o evento divergem. Em inglês, *dual write*. O Outbox resolve. |
| **Espera exponencial** | Esperar cada vez mais entre uma tentativa e outra (1 s, 2 s, 4 s...), para não sobrecarregar um serviço que está se recuperando. Em inglês, *exponential backoff*. |
| **Falha aberta** | Quando uma proteção auxiliar quebra, ela deixa passar em vez de bloquear (aqui, o Redis fora do ar não para o Pix; o banco garante sozinho). Em inglês, *fail-open*. |
| **Fallback de SPA** | Num endereço da tela que não é arquivo (`/contas/123`), o servidor devolve o `index.html`, e o roteador da tela decide o que mostrar. Precisa excluir a API e as páginas de erro: senão um erro da API volta como a tela, com status 200. |
| **Flyway** | Ferramenta que versiona o esquema do banco em scripts SQL numerados (`V1__...sql`), aplicados em ordem na subida do serviço. |
| **Idempotência** | Executar a mesma operação duas vezes tem o mesmo efeito que executar uma. Essencial quando a rede repete pedidos. |
| **Idempotency-Key** | Cabeçalho HTTP com um identificador escolhido pelo cliente; o servidor usa para reconhecer um pedido repetido. |
| **Imagem nativa** | O programa Java compilado antes, direto para código de máquina (com GraalVM), em vez de rodar sobre a JVM. Sobe em milissegundos e usa menos memória; a compilação é demorada. |
| **JdbcClient** | API do Spring para escrever SQL à mão, com parâmetros nomeados e conversão de resultados — sem a "mágica" do JPA. |
| **JIT** | *Just-in-time compiler*: a parte da JVM que, durante a execução, compila para código de máquina o código que mais roda — e às vezes apaga cálculo cujo resultado ninguém usa, o que engana medições ingênuas. |
| **JMH** | *Java Microbenchmark Harness*: ferramenta do OpenJDK para micro-benchmarks — aquece, repete, roda numa JVM separada e dá a média com a margem de erro ([ADR-0023](adr/0023-micro-benchmarks-com-jmh.md)). |
| **JPA / Hibernate** | JPA é a especificação Java para mapear objetos em tabelas; Hibernate é a implementação mais usada. O código manipula objetos, e o Hibernate gera o SQL. |
| **JWT** | *JSON Web Token*: um token em texto com três partes (cabeçalho, conteúdo e assinatura). O serviço confere a assinatura com a chave pública do Keycloak, sem perguntar nada a ele a cada pedido. |
| **Kafka** | Plataforma de eventos: serviços publicam mensagens em tópicos, outros consomem no seu ritmo. |
| **Keycloak** | Servidor de identidade de código aberto: faz o login, guarda usuários e papéis e emite os tokens OAuth2/OIDC ([ADR-0024](adr/0024-keycloak-e-realm-como-codigo.md)). |
| **KRaft** | Modo do Kafka que dispensa o ZooKeeper; o próprio Kafka coordena o cluster. |
| **Latência e percentis (p50, p95, p99)** | Latência é quanto um pedido demora. O **p99** é o tempo abaixo do qual ficam 99% dos pedidos: diz como está o cliente azarado, que a média esconde. O p50 é a mediana. |
| **Linguagem ubíqua** | No DDD, usar no código as mesmas palavras do negócio. Em inglês, *ubiquitous language*. |
| **LongAdder** | Contador do Java feito para muitas threads: em vez de um valor disputado, várias células, somadas na leitura. A mesma ideia das sub-contas. |
| **LTS** | *Long-Term Support*: versão com suporte por vários anos. |
| **Máquina de estados** | Modelo em que um objeto tem situações definidas e só muda de uma para outra por transições permitidas. |
| **Merge join** | Junção por intercalação: com as duas listas ordenadas pela mesma chave, anda-se com um dedo em cada uma, avançando a menor. Uma passada, memória constante. É o que a conciliação em lote usa. |
| **Micro-benchmark** | Medição de um pedaço pequeno de código, repetido milhões de vezes (somar valores, incrementar um contador). Ver JMH. |
| **Modelo fechado (teste de carga)** | Há sempre N clientes com um pedido em andamento; quando um termina, o mesmo cliente manda outro. Mede a vazão máxima. No modelo **aberto**, os pedidos chegam num ritmo fixo, respondidos ou não. |
| **OAuth2** | Padrão de **autorização**: em vez de dar a senha a cada sistema, a pessoa entra num servidor de identidade, e o sistema recebe um token com prazo e com o que ele pode fazer. |
| **OIDC** | *OpenID Connect*: camada sobre o OAuth2 que acrescenta a **identidade** — quem é a pessoa (o *ID token*), o login e a saída padronizados. |
| **Outbox Pattern** | Gravar o evento numa tabela "caixa de saída" na mesma transação do dado; um processo separado publica no Kafka. Garante que dado e evento nunca divergem. |
| **Paginação por cursor** | Pedir a próxima página a partir do último item visto ("depois deste"), em vez do número da página. Não repete nem pula itens quando chegam itens novos. Em inglês, *keyset pagination*. |
| **Papel (role)** | Um conjunto de permissões dado a quem entra. Aqui: `cliente` (vê o que é seu), `analista` (o backoffice) e `servico` (um serviço chamando outro). |
| **Particionamento** | Dividir os dados em partes que não se sobrepõem, para cada uma ser processada sozinha, em paralelo (aqui, faixas de ids). |
| **PKCE** | *Proof Key for Code Exchange* (fala-se "pixi"): no login, quem pediu cria um segredo e manda só o resumo dele; ao trocar o código pelo token, mostra o segredo. Um código roubado no caminho não serve para ninguém. |
| **Playwright** | Ferramenta da Microsoft que controla um navegador de verdade (aqui, o Chromium) para testes de ponta a ponta: clica, digita e confere a tela como uma pessoa. |
| **Preview (recurso do Java)** | Recurso pronto mas ainda sujeito a mudar na próxima versão. Só funciona com `--enable-preview`, na compilação e na execução. Aqui, o *Structured Concurrency*, isolado no módulo da conciliação. |
| **Problem Details** | Formato padrão (RFC 9457) de resposta de erro em APIs HTTP: `type`, `title`, `status`, `detail`. |
| **Produtor idempotente** | Configuração do produtor Kafka que numera as mensagens: se ele reenviar por falha de rede, o Kafka descarta a cópia. |
| **Projeção** | No CQRS, o código que transforma os eventos no modelo de leitura (aqui, os documentos do extrato no MongoDB). |
| **Prova reversa** | Quebrar o código de propósito para confirmar que o teste falha. Se o teste passa com o código quebrado, ele não testa nada. (A versão automatizada disso é o *mutation testing*, Fase 7.) |
| **Quarkus** | Framework Java feito para subir rápido e gastar pouca memória, com suporte a imagem nativa. Usado no serviço Pix. |
| **React** | Biblioteca JavaScript da Meta para montar telas a partir de componentes. Aqui, com TypeScript, o internet banking e o backoffice ([ADR-0029](adr/0029-react-vite-vitest-playwright.md)). |
| **Realm** | No Keycloak, um espaço isolado de usuários, papéis e clientes. Aqui, o realm `ledgerly`, guardado como código num JSON. |
| **Refresh token** | Token de prazo longo, guardado só no BFF, que serve para pedir um token de acesso novo quando o atual (5 minutos) vence — sem a pessoa digitar a senha de novo. |
| **Reserva por tempo** | Marcar um registro como "em uso por mim até tal hora". Se quem reservou morrer, a reserva vence e outro assume. Em inglês, *lease*. |
| **Resource server** | No OAuth2, o serviço que **recebe** o token e confere antes de responder (assinatura, emissor, audiência, papel). Aqui, contas, razão, pix e extrato. |
| **Saga** | Uma transação que atravessa vários serviços em passos; se um passo falha, os anteriores são desfeitos com compensações. **Orquestrada**: um coordenador conduz os passos (aqui, o serviço Pix). **Coreografada**: cada serviço reage aos eventos do outro. |
| **Scoped Values** | Valor ligado a um trecho de código (`ScopedValue.where(...).run(...)`) e visto por tudo o que roda dentro dele, inclusive as subtarefas. Substitui o `ThreadLocal` com duas vantagens: não pode ser alterado no meio do caminho e some sozinho no fim. Final no Java 25. |
| **SKIP LOCKED** | Opção do `SELECT ... FOR UPDATE` que pula as linhas já travadas por outra transação em vez de esperar. Permite vários publicadores lendo a mesma tabela de Outbox sem se atrapalhar. |
| **SPA** | *Single Page Application*: a tela carrega uma vez e, dali em diante, só troca dados com a API; a navegação entre páginas acontece no próprio navegador. |
| **Structured Concurrency** | Concorrência "estruturada": tarefas paralelas abertas dentro de um bloco (`StructuredTaskScope`), como variáveis locais. O bloco só termina quando todas terminam; se uma falha, as outras são canceladas. Nenhuma thread fica solta. Preview no Java 25. |
| **Testcontainers** | Biblioteca que sobe um banco de dados de verdade (em Docker) durante o teste. |
| **Teste de carga** | Disparar muitos pedidos ao mesmo tempo contra o serviço no ar e medir vazão e latência. Não é teste de correção, é medição. Aqui, com o Gatling ([ADR-0021](adr/0021-teste-de-carga-com-gatling.md)). |
| **Teste de ponta a ponta** | Teste que exercita o sistema inteiro no ar, pela tela, como uma pessoa faria: login, Pix, extrato. Pega o que os testes com dublês não pegam — aqui, cinco defeitos. Em inglês, *end-to-end* (E2E). |
| **Teste de propriedade** | Em vez de testar um exemplo, testa uma regra para milhares de entradas aleatórias. Em inglês, *property-based testing*. |
| **Teste instável** | Teste que às vezes passa e às vezes falha, sem mudança no código. Quase sempre é uma condição de corrida, no teste ou no produto. Não se resolve repetindo até passar: reproduz, acha a causa, corrige. Em inglês, *flaky test*. |
| **Thread de plataforma** | A *thread* tradicional do Java, uma por *thread* do sistema operacional: cara (cerca de 1 MB de pilha), por isso usada em grupos fixos (*pool*). |
| **TokenRelay** | Filtro do gateway que pega o token guardado na sessão e o põe no cabeçalho `Authorization: Bearer` do pedido repassado ao serviço. |
| **Tópico e partição** | No Kafka, o *tópico* é a fila de um assunto (`razao.lancamentos.v1`); ele é dividido em *partições*, e mensagens com a mesma chave caem sempre na mesma partição, em ordem. |
| **Trava otimista** | Não trava nada ao ler; na gravação, confere se a versão ainda é a lida. Se mudou, falha. Boa quando conflito é raro. |
| **Trava pessimista** | Trava a linha ao ler (`SELECT ... FOR UPDATE`); quem chega depois espera. Boa quando conflito é frequente. |
| **Upsert** | Gravar inserindo se não existe ou atualizando se já existe, numa operação só (*update* + *insert*). |
| **Value Object** | Objeto definido pelo valor, não por identidade, e imutável (por exemplo, `Dinheiro`). |
| **Vazão** | Quantos pedidos por segundo o serviço atende. Em inglês, *throughput*. |
| **Virtual threads** | *Threads* leves do Java 21+: permitem milhões de tarefas simultâneas sem o custo de *threads* do sistema operacional. Quando uma espera (banco, rede), sai da *thread* do sistema e deixa outra rodar. Ganham onde há espera, não onde só há cálculo ([ADR-0020](adr/0020-virtual-threads-medidas-no-razao.md)). |
| **Vite** | Ferramenta que monta a tela para produção (junta e compacta o JavaScript e o CSS) e serve a versão de desenvolvimento com recarga instantânea. |
| **Vitest** | Executor de testes de JavaScript/TypeScript feito para o Vite. Aqui, com a Testing Library, que procura os elementos pelo papel e pelo nome, como uma pessoa ("o botão Confirmar Pix"). |
