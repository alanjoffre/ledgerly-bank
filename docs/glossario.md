# Glossário

Termos de banco e de tecnologia usados no projeto, em ordem alfabética. Termos em inglês trazem a
tradução e o significado.

## Banco e finanças

| Termo | Significado |
| --- | --- |
| **Arredondamento bancário** | Arredondar "meio para o par": 2,345 → 2,34; 2,355 → 2,36. Evita que arredondamentos sucessivos puxem tudo para cima. Em inglês, *banker's rounding* ou `HALF_EVEN`. |
| **Chave Pix** | Apelido de uma conta para receber Pix: CPF, e-mail, celular ou chave aleatória. |
| **Conciliação** | Conferir que dois registros do mesmo dinheiro batem (por exemplo, o razão interno e o extrato do sistema de pagamentos). Diferença = investigar. |
| **Conta quente** | Conta que quase toda operação precisa travar (aqui, o Caixa), virando gargalo. Em inglês, *hot account*. |
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

## Tecnologia e arquitetura

| Termo | Significado |
| --- | --- |
| **ADR** | *Architecture Decision Record*: documento curto que registra uma decisão de arquitetura e seu motivo. Ver [adr/](adr/README.md). |
| **Agregado** | No DDD, um grupo de objetos tratado como unidade de consistência (por exemplo, a Conta e seus limites). Em inglês, *aggregate*. |
| **ArchUnit** | Biblioteca que testa a estrutura do código ("o domínio não pode importar Spring"). |
| **Arquitetura hexagonal** | Também chamada *Ports & Adapters*: as regras de negócio ficam no centro, sem depender de framework; banco, HTTP e Kafka são "adaptadores" nas bordas, trocáveis. |
| **Atualização perdida** | Duas transações leem o mesmo valor, as duas gravam, e a segunda apaga a mudança da primeira. Em inglês, *lost update*. |
| **BOM** | *Bill of Materials*: lista de versões de bibliotecas que combinam entre si, importada pelo Maven. |
| **C4** | Modelo de diagramas de arquitetura em quatro níveis de zoom: contexto, contêineres, componentes, código. |
| **CI** | *Continuous Integration*, integração contínua: a cada envio de código, um servidor compila e testa tudo automaticamente. |
| **Contexto delimitado** | No DDD, a fronteira dentro da qual um modelo vale. Cada serviço é um: o "Conta" do razão não é o "Conta" do cadastro. Em inglês, *bounded context*. |
| **CQRS** | *Command Query Responsibility Segregation*: separar o modelo que **grava** do modelo que **lê**, cada um otimizado para a sua tarefa. |
| **DDD** | *Domain-Driven Design*: projetar o software a partir do domínio de negócio e da sua linguagem. |
| **Deadlock** | Impasse: a transação 1 espera um recurso da 2, e a 2 espera um da 1. Nenhuma anda; o banco aborta uma delas. |
| **Defesa em profundidade** | Proteger a mesma regra em mais de uma camada (aplicação e banco), para que um erro numa não vire dano. |
| **Dublê de teste** | Peça falsa que substitui uma dependência real no teste. Um *fake* (como o `RazaoFalso`) tem comportamento simples de verdade; um *mock* só registra chamadas. |
| **Flyway** | Ferramenta que versiona o esquema do banco em scripts SQL numerados (`V1__...sql`), aplicados em ordem na subida do serviço. |
| **Idempotency-Key** | Cabeçalho HTTP com um identificador escolhido pelo cliente; o servidor usa para reconhecer um pedido repetido. |
| **Idempotência** | Executar a mesma operação duas vezes tem o mesmo efeito que executar uma. Essencial quando a rede repete pedidos. |
| **JdbcClient** | API do Spring para escrever SQL à mão, com parâmetros nomeados e conversão de resultados — sem a "mágica" do JPA. |
| **JPA / Hibernate** | JPA é a especificação Java para mapear objetos em tabelas; Hibernate é a implementação mais usada. O código manipula objetos, e o Hibernate gera o SQL. |
| **Kafka** | Plataforma de eventos: serviços publicam mensagens em tópicos, outros consomem no seu ritmo. |
| **KRaft** | Modo do Kafka que dispensa o ZooKeeper; o próprio Kafka coordena o cluster. |
| **Linguagem ubíqua** | No DDD, usar no código as mesmas palavras do negócio. Em inglês, *ubiquitous language*. |
| **LTS** | *Long-Term Support*: versão com suporte por vários anos. |
| **Máquina de estados** | Modelo em que um objeto tem situações definidas e só muda de uma para outra por transições permitidas. |
| **Outbox Pattern** | Gravar o evento numa tabela "caixa de saída" na mesma transação do dado; um processo separado publica no Kafka. Garante que dado e evento nunca divergem. |
| **Problem Details** | Formato padrão (RFC 9457) de resposta de erro em APIs HTTP: `type`, `title`, `status`, `detail`. |
| **Prova reversa** | Quebrar o código de propósito para confirmar que o teste falha. Se o teste passa com o código quebrado, ele não testa nada. (A versão automatizada disso é o *mutation testing*, Fase 7.) |
| **Saga** | Uma transação que atravessa vários serviços em passos; se um passo falha, os anteriores são desfeitos com compensações. |
| **Testcontainers** | Biblioteca que sobe um banco de dados de verdade (em Docker) durante o teste. |
| **Teste de propriedade** | Em vez de testar um exemplo, testa uma regra para milhares de entradas aleatórias. Em inglês, *property-based testing*. |
| **Trava otimista** | Não trava nada ao ler; na gravação, confere se a versão ainda é a lida. Se mudou, falha. Boa quando conflito é raro. |
| **Trava pessimista** | Trava a linha ao ler (`SELECT ... FOR UPDATE`); quem chega depois espera. Boa quando conflito é frequente. |
| **Value Object** | Objeto definido pelo valor, não por identidade, e imutável (por exemplo, `Dinheiro`). |
| **Virtual threads** | *Threads* leves do Java 21+: permitem milhões de tarefas simultâneas sem o custo de *threads* do sistema operacional. |
