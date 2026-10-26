# Glossário

Termos de banco e de tecnologia usados no projeto, em ordem alfabética. Termos em inglês trazem a
tradução e o significado.

## Banco e finanças

| Termo | Significado |
| --- | --- |
| **Arredondamento bancário** | Arredondar "meio para o par": 2,345 → 2,34; 2,355 → 2,36. Evita que arredondamentos sucessivos puxem tudo para cima. Em inglês, *banker's rounding* ou `HALF_EVEN`. |
| **Chave Pix** | Apelido de uma conta para receber Pix: CPF, e-mail, celular ou chave aleatória. |
| **Conciliação** | Conferir que dois registros do mesmo dinheiro batem (por exemplo, o razão interno e o extrato do sistema de pagamentos). Diferença = investigar. |
| **Estorno** | Desfazer uma transação com uma nova transação no sentido contrário. No razão, nada é apagado. |
| **Gasto duplo** | Gastar o mesmo saldo duas vezes, quando duas operações simultâneas leem o saldo antes de qualquer uma debitar. Em inglês, *double spending*. |
| **KYC** | *Know Your Customer*, "conheça seu cliente": verificar a identidade de quem abre conta. |
| **Lançamento** | Um registro no razão: débito ou crédito de um valor numa conta, numa data. |
| **Partidas dobradas** | Princípio contábil: todo movimento tem um débito e um crédito de mesmo valor. A soma de todos os lançamentos é sempre zero — se não for, há erro. Em inglês, *double-entry bookkeeping*. |
| **Pix** | Pagamento instantâneo brasileiro, criado pelo Banco Central, que funciona 24 horas. |
| **Razão (contábil)** | O livro onde ficam todos os lançamentos. O saldo de uma conta é a soma dos seus lançamentos. Em inglês, *ledger* — daí o nome Ledgerly. |
| **Rateio** | Dividir um valor em partes. Exige cuidado para a soma das partes fechar com o total. |
| **SPI** | Sistema de Pagamentos Instantâneos do Banco Central, que liquida o Pix entre bancos. Aqui é simulado. |

## Tecnologia e arquitetura

| Termo | Significado |
| --- | --- |
| **ADR** | *Architecture Decision Record*: documento curto que registra uma decisão de arquitetura e seu motivo. Ver [adr/](adr/README.md). |
| **Agregado** | No DDD, um grupo de objetos tratado como unidade de consistência (por exemplo, a Conta e seus limites). Em inglês, *aggregate*. |
| **ArchUnit** | Biblioteca que testa a estrutura do código ("o domínio não pode importar Spring"). |
| **BOM** | *Bill of Materials*: lista de versões de bibliotecas que combinam entre si, importada pelo Maven. |
| **C4** | Modelo de diagramas de arquitetura em quatro níveis de zoom: contexto, contêineres, componentes, código. |
| **CI** | *Continuous Integration*, integração contínua: a cada envio de código, um servidor compila e testa tudo automaticamente. |
| **CQRS** | *Command Query Responsibility Segregation*: separar o modelo que **grava** do modelo que **lê**, cada um otimizado para a sua tarefa. |
| **DDD** | *Domain-Driven Design*: projetar o software a partir do domínio de negócio e da sua linguagem. |
| **Idempotência** | Executar a mesma operação duas vezes tem o mesmo efeito que executar uma. Essencial quando a rede repete pedidos. |
| **Arquitetura hexagonal** | Também chamada *Ports & Adapters*: as regras de negócio ficam no centro, sem depender de framework; banco, HTTP e Kafka são "adaptadores" nas bordas, trocáveis. |
| **Kafka** | Plataforma de eventos: serviços publicam mensagens em tópicos, outros consomem no seu ritmo. |
| **KRaft** | Modo do Kafka que dispensa o ZooKeeper; o próprio Kafka coordena o cluster. |
| **LTS** | *Long-Term Support*: versão com suporte por vários anos. |
| **Linguagem ubíqua** | No DDD, usar no código as mesmas palavras do negócio. Em inglês, *ubiquitous language*. |
| **Outbox Pattern** | Gravar o evento numa tabela "caixa de saída" na mesma transação do dado; um processo separado publica no Kafka. Garante que dado e evento nunca divergem. |
| **Saga** | Uma transação que atravessa vários serviços em passos; se um passo falha, os anteriores são desfeitos com compensações. |
| **Teste de propriedade** | Em vez de testar um exemplo, testa uma regra para milhares de entradas aleatórias. Em inglês, *property-based testing*. |
| **Testcontainers** | Biblioteca que sobe um banco de dados de verdade (em Docker) durante o teste. |
| **Value Object** | Objeto definido pelo valor, não por identidade, e imutável (por exemplo, `Dinheiro`). |
| **Virtual threads** | *Threads* leves do Java 21+: permitem milhões de tarefas simultâneas sem o custo de *threads* do sistema operacional. |
