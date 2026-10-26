# Post do LinkedIn — Fase 0 (rascunho)

> código do `Dinheiro` com o `0.1 + 0.2`.

---

Vou construir um banco digital do zero. Em público. 🏦

Não é um banco de verdade — é um projeto de estudo. Mas com os problemas de verdade que um banco
precisa resolver: razão contábil, Pix, concorrência, antifraude com IA e nuvem.

Começo pela pergunta mais básica de um sistema financeiro: **como guardar dinheiro no código?**

Se você usa `double`, já tem um problema:

0.1 + 0.2 = 0.30000000000000004

Num banco, isso é centavo sumindo. Por isso a primeira peça do projeto é um tipo `Dinheiro` que:

✅ usa `BigDecimal`, sempre com as casas certas da moeda
✅ recusa valor com casas a mais, em vez de arredondar escondido
✅ usa arredondamento bancário (meio para o par) só onde é inevitável
✅ divide R$ 100,00 em 3 como 33,34 + 33,33 + 33,33 — sem perder o centavo
✅ não deixa somar real com dólar

E como provar que o rateio nunca perde centavo? Com um **teste de propriedade**: 10 mil valores e
divisões aleatórias, e a soma tem que bater exatamente em todas. Para ter certeza de que o teste
pega erro de verdade, quebrei o código de propósito — e 4 testes falharam na hora.

Nesta Fase 0 também ficaram prontos:

🔹 Java 25 e Maven multimódulo
🔹 PostgreSQL (um banco por serviço), MongoDB, Redis e Kafka rodando com um comando
🔹 CI no GitHub Actions
🔹 7 registros de decisão de arquitetura (ADRs), explicando o porquê de cada escolha — e o preço
   de cada uma

Próxima fase: o razão contábil em partidas dobradas, e a prova de que mil débitos simultâneos
nunca deixam o saldo negativo.

Código e documentação: https://github.com/alanjoffre/ledgerly-bank

#Java #SpringBoot #Quarkus #ArquiteturaDeSoftware #SistemasFinanceiros #BuildInPublic
