# Post do LinkedIn — Fase 7 (Qualidade)

> Imagem: [imagens/fase-7.png](imagens/fase-7.png) (fonte: [imagens/fase-7.html](imagens/fase-7.html)).
> Mutação na [ADR-0044](../adr/0044-testes-de-mutacao-com-pit.md); contratos na
> [ADR-0045](../adr/0045-contratos-entre-servicos-com-pact.md); Sonar na
> [ADR-0046](../adr/0046-sonar-local-e-condicionado-no-ci.md); o CI vermelho na
> [ADR-0043](../adr/0043-reprodutibilidade-do-modelo.md); o defeito do Kafka na
> [ADR-0047](../adr/0047-eventos-do-kafka-no-volume.md); os ensaios nos [runbooks](../runbooks/README.md).

---

Desde a primeira fase, o meu banco apagava os eventos toda vez que eu desligava o ambiente. Mais de mil testes não viram. Quem achou foi um roteiro de plantão, no dia em que resolvi executar em vez de só escrever. 🔍

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Esta fase foi desconfiar do que já estava verde.

A documentação dizia que dava para apagar o extrato sem perder nada, porque o tópico do Kafka guarda tudo para sempre. Ao seguir o roteiro, olhei o volume: vazio. O Kafka gravava dentro do contêiner, e cada desligamento levava os eventos junto. Reconstruir o extrato nesse estado apagaria o extrato e não traria nada de volta. Os testes não viam porque sempre começam com um Kafka novo. Agora os eventos ficam no volume, o CI confere a pasta, e o script se recusa a apagar o extrato se o tópico estiver vazio.

A segunda surpresa veio dos testes de mutação, que alteram o código de propósito para ver se algum teste percebe:

🔴 O CPF tinha 100% de cobertura, e 8 alterações passaram, inclusive "o dígito verificador é sempre 0". O gerador de CPF dos testes chamava a própria função testada: se o cálculo estivesse errado, ele erraria igual.
🟢 Depois de reforçar os testes do domínio: 523 de 541 mutantes mortos, 96,7%. Os 18 que sobraram estão explicados, um grupo por vez.

Mais três coisas que a fase me ensinou:

1️⃣ O CI ficou vermelho num commit que só mudava o README. O treino do modelo de risco varia até 9e-6 conforme o processador da máquina, e a folga era de 1e-6. Eu tinha prometido resultado "byte a byte". A promessa estava errada, e a correção está escrita.
2️⃣ Um teste falhava em cerca de 0,7% das execuções. Ele conferia que o saldo "999" não aparecia na resposta, e às vezes o UUID aleatório continha "999". Parecia defeito de segurança, e era o teste.
3️⃣ Renomear o campo saldo no razão agora reprova o build do próprio razão, sem subir o Pix. São 12 interações em contrato, com Pact, só no caminho do dinheiro.

Também provoquei as falhas dos roteiros, menos a quebra de integridade, que só os testes provam. Com o razão fora, o Pix respondeu 202, e concluiu 4 segundos depois da volta. Com o Kafka fora, os eventos esperaram no outbox, e a fila zerou em 4 segundos. Publiquei uma versão com imagem inexistente no Kubernetes, e o rollback voltou em 2 segundos.

O que não está resolvido, e está escrito: nada avisa que é preciso agir, porque não há alerta nenhum. O rollback só foi ensaiado com uma imagem que nem chegou a subir. O Sonar aprovou, com 92,6% de cobertura, mas o painel segue com notas C e D, porque preferi explicar os falsos positivos em vez de calá-los com anotação. E um roteiro ensaiado por quem já conhece a resposta não é um plantão de verdade.

1.141 testes automatizados: 1.061 no back-end, 73 nas telas e 7 de ponta a ponta.

Próximo passo: fechar o projeto, com a versão 1.0.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #TestesDeMutacao #Kafka #Pact #BuildInPublic
