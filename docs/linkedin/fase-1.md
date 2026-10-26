# Post do LinkedIn — lançamento (Fase 1)

> Imagem: [imagens/fase-1.png](imagens/fase-1.png) (fonte: [imagens/fase-1.html](imagens/fase-1.html)).
> Primeiro post da série; o da Fase 0 (o tipo `Dinheiro`) fica para a semana seguinte.

---

Apaguei duas palavras do meu banco digital — e ele aprovou 1.500 saques numa conta com R$ 1.000. 💸

Estou construindo um banco do zero, em público: o Ledgerly Bank. Não é um banco de verdade, mas resolve os problemas de verdade. Esta é a fase do razão contábil, o livro onde fica cada centavo que entra e sai.

O teste: uma conta com R$ 1.000,00 e 1.500 pedidos de saque de R$ 1,00 disparados ao mesmo tempo, contra um PostgreSQL de verdade.

✅ O certo: 1.000 passam, 500 são recusados, saldo final zero.
❌ Sem as duas palavras — FOR UPDATE — os 1.500 passaram.

Cada pedido leu o saldo antes de o outro gravar, e todos acharam que havia dinheiro. É o famoso gasto duplo. E eu só confio no teste porque apaguei a trava de propósito e vi ele falhar.

O mesmo experimento mostrou mais duas coisas:
🔹 400 transferências cruzadas (A→B e B→A) ao mesmo tempo: R$ 11 simplesmente sumiram.
🔹 100 cliques no mesmo botão de depósito: viraram 20 depósitos.

A correção é travar as contas, sempre na mesma ordem (a mesma ordem evita que duas transferências fiquem esperando uma pela outra para sempre). Mas toda decisão tem alternativa e preço, e eu registrei tudo: por que trava pessimista aqui e otimista no cadastro, por que não Redis, e o gargalo que essa escolha cria — que vou medir na fase de desempenho.

O que mais aprendi nesta fase:
✅ O banco de dados não deve confiar cegamente na aplicação. Saldo negativo e lançamento com débito diferente do crédito são recusados pelo próprio PostgreSQL.
✅ Teste de integração acha bug que teste de unidade não acha: o Java guarda a hora em nanossegundos, o PostgreSQL em microssegundos — e a resposta de um pedido repetido saía diferente da original.

Tudo documentado em português, com 356 testes automatizados e um script que roda o banco inteiro em 3 minutos.

Próxima fase: Pix com Quarkus, eventos com Kafka e estorno automático quando algo falha no meio do caminho.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #SpringBoot #PostgreSQL #ArquiteturaDeSoftware #BuildInPublic
