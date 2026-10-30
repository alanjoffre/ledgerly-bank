# ADR-0038: Imagens Docker em vários estágios, com camadas separadas e o Pix nativo

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

Para rodar em Kubernetes, cada serviço precisa de uma imagem. O caminho simples, um `COPY app.jar` numa
imagem com JDK, tem dois defeitos:

- **a imagem carrega o que não usa em produção:** compilador e ferramentas, que também são superfície de
  ataque;
- **qualquer mudança troca o jar inteiro:** no razao, são 55 MB de dependências que não mudaram.

A [ADR-0017](0017-quarkus-no-pix.md) mediu o Pix nativo fora de contêiner. Faltava saber se os números se
mantêm dentro dele e quanto custa construir a imagem.

## Decisão

**Um `Dockerfile` na raiz, com vários estágios (*multi-stage*)**, e `scripts/imagens.sh`, que gera
`ledgerly/<servico>:0.6.0`:

- **Construção:** JDK e `./mvnw`, com o cache do Maven em `RUN --mount=type=cache`, fora da imagem.
- **Final:** só o JRE, usuário sem privilégio (10001) e *flags* de contêiner
  (`-XX:MaxRAMPercentage=70`, `-XX:+ExitOnOutOfMemoryError`).
- **Spring Boot:** camadas extraídas com `-Djarmode=tools ... extract --layers --launcher`.
- **Quarkus:** o *fast-jar* copiado pasta por pasta, com o mesmo efeito.
- **Gateways:** um estágio Node constrói a tela, e cada BFF leva a sua.
- **Pix nativo:** a imagem Mandrel compila, e a base final é a `ubi9-quarkus-micro-image`.
- **Sem `HEALTHCHECK`:** o Kubernetes ignora essa instrução e usa as próprias sondas, e o JRE não traz
  `curl`. A saúde é verificada no chart (ADR-0039).

## Alternativas consideradas

- **Buildpacks (`spring-boot:build-image`):** menos arquivo, mas esconde as decisões, e o projeto é de
  estudo. Também não cobre o Quarkus do mesmo jeito.
- **Jib:** imagem sem Docker, sem controle do estágio da tela nem do nativo no mesmo fluxo.
- **Base Alpine:** menor, mas usa musl, e o ONNX Runtime do antifraude precisa de glibc.
- **`jlink`, um JRE sob medida:** reduziria a base de 325 MB, ao custo de manter a lista de módulos.
  Fica como melhoria possível; não foi medido.
- **Base micro UBI8 no nativo:** foi a primeira tentativa, e falhou ao rodar com `GLIBC_2.34 not found`. O
  construtor é UBI9, e a base precisa acompanhar.

## Consequências

Tamanho (soma das camadas, sem compressão) e tempo da 1ª construção, com o cache do Maven frio só na
primeira linha:

| Imagem | Camadas | `docker images` | 1ª construção |
|---|---|---|---|
| razao | 382 MB | 537 MB | 185,3 s (baixa as dependências) |
| contas | 412 MB | 595 MB | 30,8 s |
| extrato | 388 MB | 548 MB | 30,7 s |
| assistente | 364 MB | 502 MB | 13,8 s |
| antifraude | 406 MB | 586 MB | 66,3 s |
| pix (JVM) | 380 MB | 534 MB | 37,0 s |
| gateway-ib | 368 MB | 510 MB | 41,5 s |
| gateway-bo | 368 MB | 510 MB | 9,8 s |
| pix-nativo | 139 MB | 186 MB | 203,6 s |

**Com o OpenTelemetry da [ADR-0042](0042-opentelemetry-e-otel-lgtm.md),** as imagens cresceram: pix nativo de 186
para **219 MB**, pix JVM de 534 para 561 MB. Em repouso, o nativo ficou com 59 a 62 MiB no `kubectl top`. A subida, medida
com a telemetria ligada e o lgtm no ar, no mesmo cluster, 8 subidas de cada (2 cópias): **nativo de 0,09 a 0,16 s, JVM de 2,3 a 3,3 s**. Ou
seja, a telemetria não mudou a ordem de grandeza. Uma rodada anterior deu 0,28 a 0,29 s no nativo e não se repetiu, sem causa identificada; e a
JVM deu 6,3 a 6,8 s logo depois de o cluster subir, com a máquina ainda ocupada. A tabela seguinte é de antes da telemetria.

Pix JVM × nativo, medido no k3d, comparado com a ADR-0017:

| Medida | JVM aqui | Nativo aqui | JVM ADR-0017 | Nativo ADR-0017 |
|---|---|---|---|---|
| Subida | 2,27 – 2,29 s | 0,102 s | 2,2 – 2,6 s | 0,095 – 0,100 s |
| RSS em repouso | 170 – 200 MB | 127 MB | 295 – 298 MB | 79 – 83 MB |
| `kubectl top` em repouso | 175 – 205 MiB | 41 – 56 MiB | não medido | não medido |
| `kubectl top` a 100 pedidos/s | até 235 MiB | até 60 MiB | não medido | não medido |
| CPU por pod a 100 pedidos/s | 130 – 220m | cerca de 60m | não medido | não medido |
| p50 / p99 a 100 pedidos/s | 3,0 / 7,4 ms | 2,1 / 4,6 ms | não medido | não medido |
| Tamanho da imagem | 534 MB | 186 MB | não medido | não medido |
| Compilação nativa | não se aplica | 2 min 37 s, pico de 4,84 GB | não se aplica | 3 min 35 s, quase 6 GB |

- **Positivas:**
  - Mudar uma linha de código reconstrói em **20,0 s** e troca **uma camada de 594 kB**, contra 55 MB do
    jar inteiro. Sem mudança, a reconstrução leva 0,8 s.
  - O tempo de subida da ADR-0017 se confirmou em contêiner.
  - Na mesma carga, o nativo gastou menos CPU: o HPA do pix na JVM já subiu de 2 para 3 cópias, e o nativo
    ficou em 2.
- **Negativas:**
  - **A vantagem de memória depende da régua.** Pelo `kubectl top` (*working set*), o nativo usa cerca de
    1/4 da JVM, como dizia a ADR-0017. Pelo RSS, usa cerca de 2/3: 127 MB contra 170 a 200 MB. O RSS
    conta as páginas do binário de 111 MB. A JVM também gastou menos que na ADR-0017, porque o limite de
    640 MiB do contêiner restringe o *heap*.
  - Os pods nativos variaram bastante no `kubectl top` (de 14 MiB, logo ao subir, a 60 MiB), então esses números são faixas.
  - A imagem nativa leva 3 min 24 s e precisa de quase 5 GB de memória livre.
  - A base JRE pesa 325 MB dos cerca de 380 MB: as camadas economizam na troca, não no tamanho total.
