# Post do LinkedIn — Fase 2 (Pix e eventos)

> Imagem: [imagens/fase-2.png](imagens/fase-2.png) (fonte: [imagens/fase-2.html](imagens/fase-2.html)).
> Números: prova reversa na [ADR-0015](../adr/0015-saga-orquestrada-no-pix.md); medição na
> [ADR-0017](../adr/0017-quarkus-no-pix.md).

---

O razão não respondeu. Se o meu Pix estornasse "por segurança", o dinheiro sairia do bolso de outro cliente. ⏱️

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Nesta fase chegou o Pix, e com ele o primeiro problema de sistema distribuído de verdade.

Um Pix atravessa dois serviços: o pedido mora no serviço de Pix, o dinheiro mora no razão contábil. Não existe uma transação de banco que cubra os dois. Então a transferência vira uma Saga: debitar o pagador, creditar o recebedor e, se o crédito for recusado, estornar.

A armadilha está no meio: o razão demora a responder. O que fazer?

A resposta intuitiva é estornar "por segurança". Eu montei o teste: o razão credita a Maria e só depois demora a responder. Depois mudei o código de propósito para estornar nesse caso, com outro Pix em trânsito, como num banco com movimento.

❌ O João pagou R$ 80 e recebeu de volta.
❌ A Maria ficou com os R$ 80.
❌ E o estorno saiu do dinheiro de outro cliente, que estava em trânsito.

A regra que resolve: sem resposta não é recusa. Recusa é certeza de que nada se moveu; sem resposta é "não sei". A Saga repete o mesmo passo, com a mesma chave de idempotência, até ter resposta, e o razão devolve o lançamento original em vez de mover o dinheiro de novo.

Na demonstração, eu derrubo o razão no meio de um Pix: o cliente recebe "em processamento", e a transferência termina sozinha quando ele volta.

O que mais entrou nesta fase:
🔹 Outbox + Kafka: o evento é gravado na mesma transação do dado. Nunca existe "o dinheiro saiu e o evento se perdeu".
🔹 Extrato em CQRS no MongoDB, montado pelos eventos e reconstruído do zero a partir do Kafka.
🔹 Redis contra o clique duplo, com o banco como garantia: sem Redis, o Pix fica mais lento, nunca errado.
🔹 Spring Boot × Quarkus, medido: serviços com stacks parecidas ficam prontos em cerca de 6 s com Spring Boot, 2 s com Quarkus e 0,1 s com Quarkus em imagem nativa, usando 80 MB de memória.

Cada decisão está registrada com as alternativas e o preço, inclusive o limite da comparação Spring × Quarkus: não é o mesmo programa escrito duas vezes.

629 testes automatizados, tudo documentado em português.

Próxima fase: concorrência e desempenho, com virtual threads, benchmarks e teste de carga.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #Quarkus #Kafka #ArquiteturaDeSoftware #BuildInPublic
