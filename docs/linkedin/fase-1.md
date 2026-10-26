# Post do LinkedIn — Fase 1 (rascunho)

> `ORDER BY id FOR UPDATE` com o resultado do teste.

---

Removi uma linha do meu banco digital e ele aprovou 1.500 saques numa conta com R$ 1.000. 💸

Fase 1 do Ledgerly Bank, o banco que estou construindo em público: o razão contábil.

O teste é simples de explicar: uma conta com R$ 1.000,00 e 1.500 pedidos de saque de R$ 1,00
disparados ao mesmo tempo, contra um PostgreSQL de verdade.

O certo: 1.000 passam, 500 são recusados, saldo final zero.

Sem a trava de concorrência, os 1.500 passaram. Cada pedido leu o saldo antes de o outro gravar,
e todos acharam que havia dinheiro. É o famoso gasto duplo — e eu só confio no teste porque vi
ele falhar quando quebrei o código de propósito.

Mais dois resultados do mesmo experimento:

🔹 400 transferências cruzadas (A→B e B→A) ao mesmo tempo: sem a trava, R$ 11 simplesmente
sumiram. Com a trava travando sempre na mesma ordem, zero deadlock e nenhum centavo perdido.
🔹 100 cliques no mesmo botão de depósito: sem a idempotência bem feita, viraram 20 depósitos.
Com ela, 1 depósito e 99 respostas "já processado".

A solução cabe numa linha de SQL — SELECT ... ORDER BY id FOR UPDATE — mas a decisão tem
alternativas e preço, e eu registrei tudo: por que trava pessimista aqui e otimista no cadastro,
por que não Redis, e o gargalo que essa escolha cria (e que vou medir na Fase 3).

Outras coisas que aprendi nesta fase:

✅ O banco de dados não deve confiar cegamente na aplicação. Saldo negativo, lançamento com
débito diferente do crédito e alteração de lançamento antigo são recusados pelo próprio PostgreSQL.
✅ Contabilidade de verdade: partidas dobradas. Todo dinheiro que entra numa conta sai de outra, e
o balanço tem que fechar — o sistema confere isso sozinho.
✅ Teste de integração acha bug que teste de unidade não acha: o Java guarda a hora em
nanossegundos, o PostgreSQL em microssegundos, e a resposta de um pedido repetido saía diferente
da original.

Tudo documentado em português, com as decisões explicadas e um script que roda o banco inteiro em
3 minutos.

Próxima fase: Pix com Quarkus, eventos com Kafka e o padrão Saga para estornar quando algo falha
no meio do caminho.

Código e documentação: https://github.com/alanjoffre/ledgerly-bank

#Java #SpringBoot #PostgreSQL #Concorrencia #SistemasFinanceiros #BuildInPublic
