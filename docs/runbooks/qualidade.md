# Qualidade: mutação, contratos e Sonar

Rodam fora do `verify`: são lentos. O *workflow* [`qualidade.yml`](../../.github/workflows/qualidade.yml)
roda toda segunda-feira e sob demanda.

## Teste de mutação (PIT)

Muda o código de propósito (troca `>` por `>=`, devolve 0) e confere se algum teste reprova
([ADR-0044](../adr/0044-testes-de-mutacao-com-pit.md)). Só o domínio: é onde mora a regra de dinheiro.

```bash
./mvnw -Pmutacao -pl servicos/razao -am clean test-compile org.pitest:pitest-maven:mutationCoverage
```

O relatório fica em `servicos/razao/target/pit-reports/index.html`. Abaixo de 90% o módulo reprova.

**Um mutante sobrevivente** quer dizer: mudou o código e nenhum teste notou. Escreva o teste que falta
ou, se o mutante não muda o comportamento (mutante **equivalente**), anote por quê.

**O `clean` é de propósito.** O compilador da extensão Java do VS Code também grava em `target/`, e
classe velha já fez um build passar sem compilar de verdade.

## Contratos (Pact)

Cada consumidor grava em `contratos/` o que espera do provedor, e o provedor confere contra esses
arquivos ([ADR-0045](../adr/0045-contratos-entre-servicos-com-pact.md)). Os testes rodam no `verify`.

```bash
./mvnw -pl servicos/pix -am verify -Djacoco.skip=true -Dtest=NENHUM -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='Contrato*IT' -Dfailsafe.failIfNoSpecifiedTests=false
```

**Provedor reprovou:** um campo que um consumidor lê mudou. Não atualize o contrato para passar: ou
mantenha o campo, ou mude primeiro o consumidor.

**O CI reprova se `contratos/` mudou sem ser versionado:** rode os testes do consumidor e faça commit
do arquivo junto com a mudança.

## Sonar

Análise local, sem conta em nenhum serviço ([ADR-0046](../adr/0046-sonar-local-e-condicionado-no-ci.md)):

```bash
docker compose -f infra/docker/compose.yaml --profile qualidade up -d --wait sonarqube
./mvnw clean verify
./mvnw -DskipTests org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.host.url=http://localhost:19000 -Dsonar.token=<token>
docker compose -f infra/docker/compose.yaml stop sonarqube
```

Na primeira vez, entre em http://localhost:19000 (`admin`/`admin`), troque a senha e gere um token.
**Token e senha nunca vão para o repositório.** Sem o `verify` antes, a cobertura aparece quase zerada,
porque o relatório do JaCoCo não existe.

**No CI:** o job `sonar` só roda com o segredo `SONAR_TOKEN` configurado. Sem ele, só avisa.
