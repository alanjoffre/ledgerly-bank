# ADR-0017: Quarkus no Pix, com a comparação medida contra o Spring Boot

- **Status:** Aceita
- **Data:** 2026-10-27

## Contexto

A [ADR-0004](0004-cinco-servicos-com-motivo.md) separou o Pix num serviço próprio porque o tráfego
dele vem em **picos** (dia de pagamento, Black Friday): na nuvem, aguentar um pico é subir mais
cópias do serviço, e uma cópia que demora para ficar pronta chega atrasada ao pico. E o projeto
quer mostrar os dois frameworks Java mais pedidos no mercado, Spring Boot e Quarkus — com número,
não com opinião.

## Decisão

O serviço **pix** é escrito em **Quarkus 3**, com a mesma arquitetura hexagonal e o mesmo
[teste de arquitetura](../../servicos/pix/src/test/java/io/github/alanjoffre/ledgerly/pix/ArquiteturaTest.java)
dos serviços Spring: o domínio é Java puro, e a camada de aplicação só usa as anotações padrão
Jakarta (`@ApplicationScoped`, `@Transactional`). Trocar o framework mexeria nos adaptadores, não
nas regras.

| Peça | No Spring (razão, contas, extrato) | No Quarkus (pix) |
|---|---|---|
| API REST | Spring MVC | Quarkus REST (padrão Jakarta REST) |
| Banco | JdbcClient / JPA | JDBC puro sobre o pool Agroal |
| Kafka | Spring for Apache Kafka | SmallRye Reactive Messaging |
| Cliente HTTP | `RestClient` | MicroProfile REST Client (interface declarativa) |
| Contêineres nos testes | Testcontainers, configurado à mão | Dev Services (Testcontainers por baixo, sem configuração) |

Ele roda de dois jeitos: na **JVM** (`java -jar`), como os outros, e como **imagem nativa** — o
programa compilado de antemão para código de máquina, com GraalVM, num contêiner (perfil
`-Pnativo`; nada a instalar).

## A medição

[medir-subida.sh](../../scripts/medir-subida.sh) sobe cada serviço sozinho, 5 vezes, e fica com a
mediana; o script rodou duas vezes, e a tabela mostra a faixa entre as duas. **Subida** é do comando até a verificação de saúde responder "pronto" — inclui conectar no
PostgreSQL, aplicar o Flyway e entrar no Kafka, que é o que conta para receber tráfego. **Memória**
é a memória física do processo (RSS), logo depois de pronto e depois de 500 requisições.

| Serviço | Stack | Subida | Memória ao subir | Depois de 500 requisições |
|---|---|---|---|---|
| razao | Spring Boot 4, JVM | 5,9 – 6,7 s | 392 – 412 MB | 430 – 441 MB |
| extrato | Spring Boot 4, JVM | 5,4 – 6,4 s | 378 – 403 MB | 425 – 426 MB |
| pix | Quarkus 3, JVM | 2,2 – 2,6 s | 295 – 298 MB | 307 – 311 MB |
| pix | Quarkus 3, **nativo** | **0,095 – 0,100 s** | **79 – 83 MB** | **99 – 104 MB** |

Medido em 27/10/2026: notebook com WSL2 (16 núcleos, 17 GB), Java 25 (Temurin), sem limite de
memória (`-Xmx`) — a JVM escolheu o tamanho do *heap* sozinha, pela mesma regra para todos.

**O que essa tabela não diz**, para ser honesto com ela:

- **Não são o mesmo programa.** O razão e o extrato são outros serviços, com outras dependências
  (o extrato fala com o MongoDB, o Pix com Redis). As stacks são parecidas — banco, Flyway, Kafka,
  REST, verificação de saúde —, mas a comparação justa de framework seria o mesmo serviço escrito
  duas vezes, o que este projeto não faz.
- **Spring Boot também tem imagem nativa** (Spring AOT + GraalVM). A diferença grande da tabela é
  JVM × nativo, mais do que Spring × Quarkus; na JVM, o Quarkus subiu em menos da metade do tempo,
  porque faz boa parte da configuração durante o build, e não na subida.
- **Memória depois de pronto não é memória sob carga de produção.** 500 requisições mostram a
  tendência, não o teto.

## Alternativas consideradas

- **Spring Boot em todos os serviços** — uma stack só, menos coisa para aprender e manter. É a
  escolha certa para a maioria das equipes; aqui, perderia metade do que o projeto quer mostrar e o
  ganho de subida medido acima.
- **Spring Boot com imagem nativa no Pix** — possível; fica de fora porque o Quarkus foi feito com a
  imagem nativa como caminho principal, e a ADR-0004 já previa os dois frameworks.
- **Quarkus em todos os serviços** — o razão e o serviço de contas já existiam em Spring, e não
  precisam subir rápido: não escalam por pico do mesmo jeito.

## Consequências

- **Positivas:**
  - nativo, o Pix fica pronto em cerca de um décimo de segundo, com pouco mais de um quarto da
    memória do mesmo serviço na JVM: uma cópia nova entra no pico quase na hora, e cabem mais cópias
    na mesma máquina;
  - na JVM, o Quarkus já sobe em menos da metade do tempo dos serviços Spring, sem mudar nada no
    código.
- **Negativas:**
  - **duas stacks** para conhecer: cada uma resolve o mesmo problema de um jeito (o Outbox tem um
    publicador em cada uma, por exemplo — [ADR-0013](0013-outbox-para-publicar-eventos.md));
  - o agendador do Quarkus não aceita intervalo menor que 1 s, e o publicador do Outbox precisa de
    100 ms: ele usa um agendador próprio, com *virtual threads*;
  - a **compilação nativa é cara**: 3 min 35 s e quase 6 GB de memória, contra segundos do pacote
    da JVM. Por isso o build do dia a dia e o CI usam a JVM, e a imagem nativa entra nas imagens
    Docker da Fase 6;
  - a imagem nativa precisa saber de antemão tudo que usa reflexão; uma biblioteca que não declara
    isso funciona na JVM e quebra no nativo. Os testes rodam na JVM — o nativo é conferido pela
    subida e pela verificação de saúde. **Na Fase 6 não ganhou teste próprio no build:** a prova são os
    7 testes de ponta a ponta contra o cluster, que usa a imagem nativa ([ADR-0038](0038-imagens-multi-stage-e-nativa.md)).
