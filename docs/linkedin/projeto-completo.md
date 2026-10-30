# Post do LinkedIn — O projeto completo

> Imagem: [imagens/projeto-completo.png](imagens/projeto-completo.png) (fonte:
> [imagens/projeto-completo.html](imagens/projeto-completo.html)). Os números e as provas estão no
> [README](../../README.md) e nas [ADRs](../adr/README.md).

---

Tirei a trava de propósito do meu banco, e 1.500 saques de R$ 1,00 foram aprovados numa conta com R$ 1.000,00. R$ 500 de gasto duplo. Foi assim que eu soube que o teste de concorrência pegava o erro. 🏦

O Ledgerly Bank está completo. É um banco digital fictício, construído do zero e em público, com dados sintéticos, para estudar engenharia de software de nível sênior. Ele não movimenta dinheiro de verdade. A regra era outra: nenhuma afirmação sem prova.

O que tem dentro: razão contábil em partidas dobradas, contas, Pix com estorno automático, extrato em tempo real, antifraude com IA, um assistente que só lê, e duas telas em React com login no Keycloak. São 7 serviços em Java 25, com Spring Boot e Quarkus, ligados por Kafka.

Cada fase deixou uma prova:

🔹 Razão: sem a trava, R$ 500 de gasto duplo. Com ela, 1.000 aprovados e 500 recusados.
🔹 Pix: sem resposta não é recusa. No timeout, o sistema repete o mesmo passo com a mesma chave, porque estornar ali poderia devolver um dinheiro que chegou.
🔹 Desempenho: uma única linha do banco era o gargalo. Dividida em 16 sub-contas, foi de 99 para 489 depósitos por segundo.
🔹 Segurança e telas: todos os testes passavam, até um navegador de verdade fazer login, enviar um Pix e abrir o extrato: 5 defeitos.
🔹 IA: o Pix suspeito fica retido antes do débito, com o modelo de risco rodando em cerca de 6,5 µs. O assistente acerta 27 de 30 perguntas numa avaliação fixa.
🔹 Operação: com o antifraude travado, de 18 para mais de 121 Pix por segundo. O preço também está medido: mais Pix sem nota de risco.
🔹 Qualidade: 523 de 541 mutantes mortos. E o ensaio de um roteiro de plantão achou um defeito de sete fases: o Kafka apagava os eventos a cada desligamento.

As lições que levo são menos sobre tecnologia:

1️⃣ Um teste só vale depois que você o viu falhar pelo motivo certo.
2️⃣ Medir engana. Já medi um custo de 25% que não existia: era a JVM aquecendo.
3️⃣ O falso prova o código contra o falso. Banco de verdade, navegador de verdade e modelo de verdade acharam o que os dublês escondiam.
4️⃣ Escreva o preço de cada decisão. São 47 ADRs, e cada uma tem as alternativas descartadas e o que custou.

E o que ele não é, porque isso também faz parte: nunca teve um cliente nem um plantão de verdade. A AWS está em Terraform, com 230 recursos validados, e nunca foi aplicada. Não há alerta nenhum. A conciliação roda sobre dados sintéticos. Tudo isso está escrito, com o que faltaria para produção.

1.141 testes automatizados, 92,6% de cobertura, custo zero de nuvem, e tudo documentado em português, com os termos em inglês explicados.

Quem quiser olhar, criticar ou aprender junto: o código, as decisões e os roteiros estão abertos.

🔗 github.com/alanjoffre/ledgerly-bank

#Java #EngenhariaDeSoftware #SistemasDistribuidos #Kafka #BuildInPublic
