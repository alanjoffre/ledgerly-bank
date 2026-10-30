# Imagens Docker do Ledgerly Bank (Fase 6, ADR-0038). Um arquivo só, com vários ESTÁGIOS.
#
# O que é um build *multi-stage* (em vários estágios): cada "FROM" abre um estágio novo, com a sua
# própria imagem de base. Os estágios de BUILD têm o JDK, o Maven e o Node — tudo o que compila. O
# estágio FINAL começa de uma imagem limpa, só com o JRE (o Java que roda, sem o compilador), e copia
# do estágio de build apenas o resultado. Ferramenta de compilação, código-fonte e cache do Maven
# ficam para trás: a imagem final é menor e tem menos coisa para um atacante usar.
#
# Um estágio final por família (escolhido com --target):
#
#   spring      razao, contas, extrato, assistente     docker build --target spring --build-arg SERVICO=razao .
#   gateway     os dois BFFs, cada um com a sua tela   docker build --target gateway --build-arg TELA=internet-banking .
#   quarkus     pix e antifraude, na JVM               docker build --target quarkus --build-arg SERVICO=pix .
#   pix-nativo  o pix como imagem nativa (GraalVM)     docker build --target pix-nativo .
#
# O jeito do dia a dia é o scripts/imagens.sh, que constrói todas com a tag ledgerly/<serviço>:0.6.0.
#
# HEALTHCHECK: nenhuma imagem declara. O Kubernetes NÃO usa o HEALTHCHECK do Dockerfile (o containerd
# ignora); quem pergunta se o serviço está vivo e pronto são as *probes* do Helm chart
# (infra/k8s/ledgerly), que conhecem o endereço certo de cada um. E a imagem do JRE não traz curl:
# um HEALTHCHECK exigiria instalar uma ferramenta só para isso.
#
# Versões exatas (nunca "latest"): a mesma imagem de base hoje e daqui a um ano. Conferidas em
# 30/10/2026. "noble" = Ubuntu 24.04, com glibc — o antifraude usa o ONNX Runtime, que traz uma
# biblioteca nativa compilada para glibc e não roda no Alpine (musl).

ARG JDK=eclipse-temurin:25.0.5_8-jdk-noble
ARG JRE=eclipse-temurin:25.0.5_8-jre-noble
ARG NODE=node:22.23.4-bookworm-slim
ARG MANDREL=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:25.0.4.1-Final-java25
ARG MICRO=quay.io/quarkus/ubi9-quarkus-micro-image:2.0-2026-10-25

# ---------------------------------------------------------------------------------------------
# Estágio "poms": só os arquivos do Maven, sem o código. Fica em cache enquanto nenhum pom.xml mudar.
# O POM pai lista todos os módulos; o Maven exige que cada um exista, mesmo os que não compilamos
# aqui (desempenho, lotes) — por isso eles entram, mas só o pom.xml.
# ---------------------------------------------------------------------------------------------
FROM ${JDK} AS poms
WORKDIR /fonte
COPY mvnw pom.xml ./
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
COPY bibliotecas/nucleo-compartilhado/pom.xml bibliotecas/nucleo-compartilhado/
COPY bibliotecas/seguranca-spring/pom.xml bibliotecas/seguranca-spring/
COPY servicos/razao/pom.xml servicos/razao/
COPY servicos/contas/pom.xml servicos/contas/
COPY servicos/pix/pom.xml servicos/pix/
COPY servicos/extrato/pom.xml servicos/extrato/
COPY servicos/gateway/pom.xml servicos/gateway/
COPY servicos/antifraude/pom.xml servicos/antifraude/
COPY servicos/assistente/pom.xml servicos/assistente/
COPY desempenho/carga/pom.xml desempenho/carga/
COPY desempenho/jmh/pom.xml desempenho/jmh/
COPY lotes/conciliacao/pom.xml lotes/conciliacao/

# ---------------------------------------------------------------------------------------------
# Estágio "build": compila UM serviço (e as bibliotecas de que ele depende: -am, "also make").
#
# Copia só a pasta do serviço pedido: mudar o pix não invalida o cache do razão.
#
# --mount=type=cache: a pasta ~/.m2 (as dependências baixadas do Maven Central) fica num cache do
# BuildKit que SOBREVIVE entre builds e é dividido por todas as imagens — a primeira baixa, as outras
# reaproveitam. Ela não entra em camada nenhuma da imagem. sharing=locked: dois builds ao mesmo tempo
# esperam a vez, em vez de dois Maven gravarem o mesmo arquivo do cache juntos.
# ---------------------------------------------------------------------------------------------
FROM poms AS build
ARG SERVICO
COPY bibliotecas bibliotecas
COPY servicos/${SERVICO} servicos/${SERVICO}
RUN --mount=type=cache,target=/root/.m2,id=ledgerly-m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -q -pl servicos/${SERVICO} -am package -DskipTests

# ---------------------------------------------------------------------------------------------
# Estágio "camadas-spring": desmonta o jar do Spring Boot em CAMADAS (*layers*).
#
# Por quê: o jar executável é um arquivo só, com ~55 MB de dependências e ~600 KB do nosso código.
# Copiado inteiro, uma linha mudada gera uma camada nova de 55 MB. Extraído em camadas, cada uma vira
# uma camada da imagem, da que muda menos para a que muda mais:
#   dependencies           bibliotecas com versão fechada (Spring, Jackson, driver do Postgres...)
#   spring-boot-loader     o lançador do Spring Boot
#   snapshot-dependencies  bibliotecas -SNAPSHOT (as nossas: nucleo-compartilhado, seguranca-spring)
#   application            as classes e o application.yaml do serviço
# Uma linha mudada no razão = só a camada "application" muda; as outras vêm do cache.
# ---------------------------------------------------------------------------------------------
FROM build AS camadas-spring
ARG SERVICO
RUN jar=$(ls servicos/${SERVICO}/target/${SERVICO}-*.jar | grep -v original | head -1) && \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /extraido

# ---------------------------------------------------------------------------------------------
# Estágio "tela": constrói a tela React (Vite) que o gateway serve.
# Primeiro os package.json e o package-lock.json, e o "npm ci" (instala exatamente o que está
# travado no lock); só depois o código. Mudar uma tela não reinstala o npm.
# ---------------------------------------------------------------------------------------------
FROM ${NODE} AS tela
ARG TELA
WORKDIR /web
COPY web/package.json web/package-lock.json web/tsconfig.base.json ./
COPY web/comum/package.json comum/
COPY web/internet-banking/package.json internet-banking/
COPY web/backoffice/package.json backoffice/
COPY web/e2e/package.json e2e/
RUN --mount=type=cache,target=/root/.npm,id=ledgerly-npm \
    npm ci --no-audit --no-fund
COPY web/comum comum
COPY web/${TELA} ${TELA}
RUN npm run construir -w ${TELA}

# ---------------------------------------------------------------------------------------------
# Base de execução da JVM, comum ao Spring e ao Quarkus.
#
# Usuário não root (uid 10001): se alguém explorar uma falha do serviço, não é root no contêiner.
# Número, e não nome, para o Kubernetes conseguir conferir "runAsNonRoot".
#
# JAVA_TOOL_OPTIONS: a JVM lê sozinha esta variável (e o Kubernetes pode trocá-la sem rebuild).
#   -XX:MaxRAMPercentage=70   o heap cresce até 70% do LIMITE DE MEMÓRIA DO CONTÊINER (não da
#                             máquina). Os outros 30% são do metaspace, das threads, do código
#                             compilado e dos buffers de rede — memória fora do heap, que existe.
#   -XX:+ExitOnOutOfMemoryError  sem memória, o processo morre na hora e o Kubernetes sobe outro,
#                             em vez de ficar meio vivo respondendo erro.
#   -Djava.security.egd=...   não espera a "entropia" do sistema para gerar números aleatórios.
# ---------------------------------------------------------------------------------------------
FROM ${JRE} AS base-jvm
RUN groupadd --system --gid 10001 ledgerly && \
    useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin ledgerly
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
LABEL org.opencontainers.image.source="https://github.com/alanjoffre/ledgerly-bank" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="0.6.0"
USER 10001:10001

# ---------------------------------------------------------------------------------------------
# FINAL: serviço Spring Boot (razao, contas, extrato, assistente). Uma camada por pasta extraída.
# ---------------------------------------------------------------------------------------------
FROM base-jvm AS spring
COPY --from=camadas-spring /extraido/dependencies/ ./
COPY --from=camadas-spring /extraido/spring-boot-loader/ ./
COPY --from=camadas-spring /extraido/snapshot-dependencies/ ./
COPY --from=camadas-spring /extraido/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# ---------------------------------------------------------------------------------------------
# FINAL: gateway (BFF). O MESMO jar nos dois BFFs; muda o perfil e a tela que vai junto.
#   TELA=internet-banking → perfil internet-banking, porta 8080
#   TELA=backoffice       → perfil backoffice, porta 8090
# As camadas do jar são idênticas nas duas imagens: o Docker guarda uma vez só.
# ---------------------------------------------------------------------------------------------
FROM poms AS build-gateway
COPY servicos/gateway servicos/gateway
RUN --mount=type=cache,target=/root/.m2,id=ledgerly-m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -q -pl servicos/gateway -am package -DskipTests && \
    jar=$(ls servicos/gateway/target/gateway-*.jar | grep -v original | head -1) && \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /extraido

FROM base-jvm AS gateway
ARG TELA
COPY --from=build-gateway /extraido/dependencies/ ./
COPY --from=build-gateway /extraido/spring-boot-loader/ ./
COPY --from=build-gateway /extraido/snapshot-dependencies/ ./
COPY --from=build-gateway /extraido/application/ ./
COPY --from=tela /web/${TELA}/dist/ /app/tela/
ENV SPRING_PROFILES_ACTIVE=${TELA} \
    GATEWAY_PAGINA=file:/app/tela/
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# ---------------------------------------------------------------------------------------------
# FINAL: serviço Quarkus na JVM (pix, antifraude).
#
# O Quarkus já empacota em pastas (o *fast-jar*, target/quarkus-app): lib/ (dependências), app/
# (o nosso código), quarkus/ (o que o Quarkus gerou no build) e o quarkus-run.jar que liga tudo.
# Copiadas da que muda menos (lib) para a que muda mais (app): o mesmo ganho de cache do Spring.
# ---------------------------------------------------------------------------------------------
FROM base-jvm AS quarkus
ARG SERVICO
COPY --from=build /fonte/servicos/${SERVICO}/target/quarkus-app/lib/ /app/lib/
COPY --from=build /fonte/servicos/${SERVICO}/target/quarkus-app/quarkus-run.jar /app/
COPY --from=build /fonte/servicos/${SERVICO}/target/quarkus-app/quarkus/ /app/quarkus/
COPY --from=build /fonte/servicos/${SERVICO}/target/quarkus-app/app/ /app/app/
# O LogManager do Quarkus, como recomenda a imagem oficial (sem ele, parte do log sai sem formato).
ENTRYPOINT ["java", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]

# ---------------------------------------------------------------------------------------------
# Imagem NATIVA do pix (ADR-0017): o programa compilado de antemão para código de máquina.
#
# Em dois passos, para não precisar do Maven dentro da imagem do GraalVM:
#   1. no JDK, o Maven gera as "fontes da compilação nativa" (quarkus.native.sources-only): o jar,
#      as dependências e o arquivo native-image.args com os parâmetros que o Quarkus calculou;
#   2. na imagem do Mandrel (a distribuição do GraalVM que o Quarkus mantém), o native-image compila.
# O passo 2 é o caro: ~3 a 4 minutos e alguns GB de memória (o -J-Xmx abaixo põe um teto de 5 GB).
# ---------------------------------------------------------------------------------------------
FROM poms AS fontes-nativas
COPY bibliotecas bibliotecas
COPY servicos/pix servicos/pix
RUN --mount=type=cache,target=/root/.m2,id=ledgerly-m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -q -pl servicos/pix -am package -DskipTests \
      -Pnativo -Dquarkus.native.container-build=false -Dquarkus.native.sources-only=true \
      -Dquarkus.native.native-image-xmx=5g

FROM ${MANDREL} AS build-nativo
# A imagem do Mandrel roda como o usuário "quarkus"; o root é só para gravar a saída nesta pasta.
# Este estágio não vai para a imagem final: só o executável sai daqui.
USER root
WORKDIR /nativo
COPY --from=fontes-nativas --chown=1001:1001 /fonte/servicos/pix/target/native-sources/ ./
# A imagem do Mandrel tem o native-image como ENTRYPOINT; aqui ele roda num RUN, com os argumentos
# que o Quarkus escreveu. O executável sai com o nome do jar de entrada, sem o ".jar".
RUN native-image $(cat native-image.args) && \
    mv "$(ls *-runner | head -1)" /nativo/pix

# A base final é a "micro" do Quarkus: um Linux mínimo (UBI 9 micro, ~40 MB) só com a glibc e o que um
# executável nativo precisa. Não tem Java, nem gerenciador de pacotes, nem curl.
# Tem de ser a UBI **9**, a mesma família do Mandrel que compilou: o executável usa a glibc 2.34 do
# estágio de build. Com a quarkus-micro-image (UBI 8, glibc 2.28), ele nem abre:
# "GLIBC_2.34 not found" — erro que só aparece ao rodar, não ao construir.
FROM ${MICRO} AS pix-nativo
WORKDIR /app
COPY --from=build-nativo --chown=10001:0 --chmod=0755 /nativo/pix /app/pix
LABEL org.opencontainers.image.source="https://github.com/alanjoffre/ledgerly-bank" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="0.6.0"
USER 10001:0
# O heap do nativo já respeita o limite do contêiner sozinho (por padrão, até 80% dele); para outro
# teto, o Kubernetes passa -Xmx nos argumentos.
ENTRYPOINT ["/app/pix"]
