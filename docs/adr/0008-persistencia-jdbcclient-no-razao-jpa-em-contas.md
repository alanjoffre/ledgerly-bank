# ADR-0008: Persistência — SQL explícito no razão, JPA no serviço de contas

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Os dois serviços da Fase 1 têm necessidades diferentes:

- o **razão** é o caminho mais disputado do banco. Cada operação trava linhas em ordem
  (`SELECT ... FOR UPDATE`), confere saldo e grava lançamentos que nunca mudam. É preciso saber
  exatamente que SQL roda e quando;
- o serviço de **contas** é um cadastro clássico (cliente, conta, situação), com pouca disputa e
  muitas leituras e gravações simples.

## Decisão

- **Razão: `JdbcClient` do Spring** (SQL escrito à mão). A trava, a ordem das linhas e cada
  `INSERT` ficam visíveis no código ([JdbcContaRazaoRepository](../../servicos/razao/src/main/java/io/github/alanjoffre/ledgerly/razao/adaptadores/saida/persistencia/JdbcContaRazaoRepository.java)).
- **Contas: JPA/Hibernate com Spring Data**, com `ddl-auto=validate` (o Hibernate só confere se as
  entidades batem com as tabelas; quem cria e muda tabela é sempre o Flyway).
- Nos dois, **as entidades de banco ficam separadas do domínio**: o domínio é Java puro; o
  adaptador de persistência traduz.
- A camada de aplicação pode usar só duas coisas do Spring: `@Service` e `@Transactional`.
  O teste de arquitetura (ArchUnit) barra qualquer outra.

## Alternativas consideradas

- **JPA nos dois** — no razão, o Hibernate decide quando fazer o `flush` e pode reordenar
  gravações; travar linhas em ordem fixa fica implícito e frágil.
- **SQL explícito nos dois** — no cadastro, seria código repetitivo sem ganho. E JPA é o que a
  maioria das vagas pede: o projeto mostra as duas ferramentas, cada uma onde faz sentido.
- **Domínio anotado com JPA** (entidade = domínio) — menos código, mas o domínio passa a depender
  do banco, e regras como "conta só nasce ativa" ficam expostas a um construtor vazio exigido
  pelo JPA.

## Consequências

- **Positivas:** o SQL crítico é auditável; o domínio é testável sem banco (milissegundos).
- **Negativas:** há tradução entre entidade e domínio (`ContaEntidade` ↔ `Conta`). O preço é
  pequeno e localizado num pacote só.
