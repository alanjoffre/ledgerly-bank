# Guia: ambiente local

Passo a passo para rodar o Ledgerly Bank na sua máquina. Testado em Linux (WSL2 no Windows).

## 1. O que instalar

| Ferramenta | Versão | Para quê |
| --- | --- | --- |
| **JDK** | 25 | Compilar e rodar Java. Recomendado: [Eclipse Temurin](https://adoptium.net/). |
| **Docker** + Docker Compose | 24+ / v2+ | Rodar bancos de dados, Kafka e Keycloak. |
| **Node.js** | 22+ | Construir e testar as telas (`web/`). Só para elas: o build Java não precisa. |
| **Git** | qualquer recente | Clonar o repositório. |

O **Maven não precisa ser instalado**: o projeto traz o Maven Wrapper (`./mvnw`), que baixa a
versão certa sozinho na primeira execução.

### Instalar o JDK 25 sem permissão de administrador (Linux)

```bash
mkdir -p ~/.local/share/java && cd ~/.local/share/java
curl -fsSL -o jdk25.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk25.tar.gz && rm jdk25.tar.gz
ln -sfn "$(ls -d jdk-25* | head -1)" current
```

E no fim do `~/.bashrc` (ou `~/.zshrc`):

```bash
export JAVA_HOME="$HOME/.local/share/java/current"
export PATH="$JAVA_HOME/bin:$PATH"
```

Abra um terminal novo e confira com `java -version` (deve mostrar `25`).

## 2. Compilar e testar

Na raiz do projeto:

```bash
./mvnw verify
```

O `verify` compila, roda todos os testes, mede a cobertura e confere as regras de arquitetura.
Leva uns 4 minutos e **precisa do Docker rodando**: os testes de integração sobem PostgreSQL,
MongoDB, Kafka e Redis de verdade, em contêineres descartáveis (Testcontainers nos serviços Spring,
Dev Services no Quarkus). Não precisa subir a infraestrutura do passo 3 antes.

O relatório de cobertura de cada módulo fica em `<módulo>/target/site/jacoco/index.html`.

## 3. Subir a infraestrutura

```bash
docker compose -f infra/docker/compose.yaml up -d --wait
docker compose -f infra/docker/compose.yaml ps
```

| Serviço | Endereço na sua máquina | Usuário / senha (só local) |
| --- | --- | --- |
| PostgreSQL | `localhost:15432` | bancos `contas`, `razao`, `pix` — usuário = nome do banco, senha = `<banco>_local` |
| MongoDB | `localhost:17017` | `ledgerly_admin` / `ledgerly_local`; o extrato usa `extrato` / `extrato_local`, só no banco `extrato` |
| Redis | `localhost:16379` | sem senha |
| Kafka | `localhost:19092` | sem autenticação; os tópicos são criados na subida ([criar-topicos.sh](../../infra/docker/kafka/criar-topicos.sh)) |
| Keycloak | http://localhost:18081 | console: `admin` / `admin`; o realm `ledgerly` (papéis, pessoas e clientes) é importado na primeira subida de [ledgerly-realm.json](../../infra/docker/keycloak/ledgerly-realm.json) |
| Kafka UI (opcional) | http://localhost:18080 | sobe com `--profile ferramentas` |

As portas começam com `1` para não colidir com um Postgres, Mongo ou Redis que já exista na máquina.

Para parar: `docker compose -f infra/docker/compose.yaml down` (os dados ficam).
Para zerar tudo: acrescente `-v` (**apaga os dados**).

A criação automática de tópicos do Kafka está **desligada**: um tópico com o nome digitado errado
seria criado em silêncio, e o evento iria para um lugar que ninguém lê. Os seis tópicos (três de
eventos e um `.dlt` para cada) são criados pelo contêiner `kafka-topicos`, e o `--wait` só termina
depois que ele acaba.

## 4. Subir os serviços

```bash
./mvnw package -DskipTests                 # gera os pacotes (sem rodar os testes)
(cd web && npm ci && npm run construir)    # as telas, que os gateways servem
./scripts/servicos.sh subir                # sobe os quatro serviços e os dois gateways, e espera cada um ficar saudável
./scripts/servicos.sh situacao             # quem está no ar
./scripts/servicos.sh parar                # derruba todos
```

| Serviço | Endereço | Saúde |
| --- | --- | --- |
| contas | http://localhost:8081 | `/actuator/health` |
| razao | http://localhost:8082 | `/actuator/health` |
| pix | http://localhost:8083 | `/q/health` |
| extrato | http://localhost:8084 | `/actuator/health` |
| gateway-ib (internet banking) | http://localhost:8080 | `/actuator/health` |
| gateway-bo (backoffice) | http://localhost:8090 | `/actuator/health` |

Desde a Fase 4, os serviços exigem um token do Keycloak (`Authorization: Bearer`). Os scripts de
demonstração pedem um token de serviço sozinhos ([scripts/lib/token.sh](../../scripts/lib/token.sh)).
As telas fazem login pelo gateway: rode `./scripts/semear.sh` para a Ana e o Bruno terem conta e
saldo, e veja a [demonstração da Fase 4](demo-fase-4.md).

Também dá para subir ou derrubar um só (`./scripts/servicos.sh parar razao`), para ver o que os
outros fazem sem ele. Os logs ficam em `.servicos/<serviço>.log`.

O que fazer com os serviços no ar: [demonstração da Fase 2](demo-fase-2.md).

### Imagem nativa do Pix (opcional)

```bash
./mvnw -pl servicos/pix -am package -Pnativo -DskipTests
./servicos/pix/target/pix-0.1.0-SNAPSHOT-runner
```

A compilação roda num contêiner com GraalVM (nada a instalar), leva uns 4 minutos e usa até 6 GB
de memória. O resultado é um executável que não precisa de Java para rodar.

## Problemas comuns

- **`O Ledgerly Bank exige Java 25 ou superior`** — o `JAVA_HOME` aponta para outro JDK. Confira
  com `echo $JAVA_HOME` e `java -version`.
- **Porta já em uso** — outro programa usa a porta. Veja quem com `ss -ltnp | grep 15432` e troque
  a porta no `compose.yaml` (o lado esquerdo do `"127.0.0.1:15432:5432"`).
- **Mudei o script de `postgres/init` e nada aconteceu** — ele só roda com o volume vazio.
  Rode `down -v` e suba de novo (apaga os dados locais).
- **O extrato não sobe: `Authentication failed`** — o volume do MongoDB é anterior à Fase 2 e não
  tem o usuário `extrato`. Crie sem apagar nada com o comando no topo de
  [01-usuario-extrato.js](../../infra/docker/mongo/init/01-usuario-extrato.js).
- **Mudei o `ledgerly-realm.json` e nada aconteceu** — o realm só é importado se ainda não existir.
  Rode `down -v` e suba de novo (apaga os dados locais, inclusive o vínculo das pessoas com as
  contas; rode `./scripts/semear.sh` outra vez).
- **Tudo responde `401`** — o token não foi aceito, ou nem foi pedido. O Keycloak está no ar
  (`docker compose ps`)? O serviço confere o emissor (`http://localhost:18081/realms/ledgerly`), a
  validade e a audiência: um token vencido, de outro realm ou de outro Keycloak é recusado. O motivo
  exato fica só no log do serviço; a resposta não diz, de propósito, para não ensinar o atacante.
- **O gateway responde "a tela não foi construída"** — rode `npm run construir` em `web/`.
- **Um serviço não sobe pelo `servicos.sh`** — veja o fim de `.servicos/<serviço>.log`. O mais
  comum é a infraestrutura não estar no ar, ou a porta estar ocupada por um serviço iniciado à mão.
