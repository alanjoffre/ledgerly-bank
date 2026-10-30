# Guia: demonstração da Fase 7

Mostra, na sua máquina, como se confere se os testes prestam:

- os **testes de mutação**: mudar o código de propósito e ver se algum teste percebe;
- os **contratos**: renomear um campo e o build do serviço reprovar, sem subir o sistema;
- a **análise estática** num SonarQube local;
- um **runbook** seguido com a falha provocada de verdade;
- o **vídeo**: o navegador usando o banco, gravado pelos testes de ponta a ponta.

Faça só a parte que interessa. Os números e o porquê estão em
[ADR-0043](../adr/0043-reprodutibilidade-do-modelo.md) (modelo),
[ADR-0044](../adr/0044-testes-de-mutacao-com-pit.md) (mutação),
[ADR-0045](../adr/0045-contratos-entre-servicos-com-pact.md) (contratos) e
[ADR-0046](../adr/0046-sonar-local-e-condicionado-no-ci.md) (Sonar).

## 1. Um teste que não testava

```bash
./mvnw -Pmutacao -pl servicos/contas -am clean test-compile org.pitest:pitest-maven:mutationCoverage
```

Abra `servicos/contas/target/pit-reports/index.html`. Em verde, o que foi mudado e algum teste percebeu;
em vermelho, o que sobreviveu. Agora faça o defeito que a mutação achou nesta fase: em
`servicos/contas/src/test/java/.../contas/dominio/CpfsDeTeste.java`, chame `Cpf` para calcular o dígito, em
vez da fórmula independente, e apague de `CpfTest` o `contaDoDigito`, que tem os valores calculados à mão. Rode de
novo: a cobertura de linhas segue em 98%, mas 8 mutantes de `Cpf` sobrevivem, o módulo cai de 96,8% para 84%, e o limite de
90% reprova o comando.

## 2. Um contrato reprovando

No razão, renomeie o campo `saldo` da resposta em
`servicos/razao/src/main/java/.../razao/adaptadores/entrada/web/Dtos.java` e rode só o contrato:

```bash
./mvnw -pl servicos/razao -am verify -Djacoco.skip=true -Dtest=NENHUM -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ContratoIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Reprova e diz qual consumidor esperava o campo, sem o Pix nem o contas no ar. Desfaça a mudança.

## 3. O SonarQube

Siga [o runbook de qualidade](../runbooks/qualidade.md). Depois da análise, abra http://localhost:19000.

## 4. Um runbook na prática

Com o sistema no ar (como no [guia de ambiente local](ambiente-local.md)), pare o razão, faça um Pix e siga
[Razão fora do ar](../runbooks/razao-fora-do-ar.md). A demonstração da Fase 2 faz o mesmo caminho sozinha:

```bash
./scripts/demo-fase-2.sh
```

## 5. O vídeo

Com o sistema no ar e semeado, a variável `VIDEO=1` grava cada teste do navegador:

```bash
cd web
VIDEO=1 npm run e2e
ls e2e/test-results/*/video.webm
```

Um vídeo por teste, em 1280x720.

**Rode uma vez e espere uma hora para repetir, ou comece com um ambiente novo.** Os testes fazem Pix com a conta da Ana,
e o antifraude segura o Pix a partir do 6º na mesma hora (a regra de rajada). Na repetição, o teste do internet banking
falha com "Pix retido para análise de segurança", e o sistema está certo.
