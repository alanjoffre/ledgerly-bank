# Guia: ambiente local

Passo a passo para rodar o Ledgerly Bank na sua máquina. Testado em Linux (WSL2 no Windows).

## 1. O que instalar

| Ferramenta | Versão | Para quê |
| --- | --- | --- |
| **JDK** | 25 | Compilar e rodar Java. Recomendado: [Eclipse Temurin](https://adoptium.net/). |
| **Docker** + Docker Compose | 24+ / v2+ | Rodar bancos de dados e Kafka. |
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
O relatório de cobertura fica em `bibliotecas/nucleo-compartilhado/target/site/jacoco/index.html`.

## 3. Subir a infraestrutura

```bash
docker compose -f infra/docker/compose.yaml up -d --wait
docker compose -f infra/docker/compose.yaml ps
```

| Serviço | Endereço na sua máquina | Usuário / senha (só local) |
| --- | --- | --- |
| PostgreSQL | `localhost:15432` | bancos `contas`, `razao`, `pix` — usuário = nome do banco, senha = `<banco>_local` |
| MongoDB | `localhost:17017` | `ledgerly_admin` / `ledgerly_local` |
| Redis | `localhost:16379` | sem senha |
| Kafka | `localhost:19092` | sem autenticação |
| Kafka UI (opcional) | http://localhost:18080 | sobe com `--profile ferramentas` |

As portas começam com `1` para não colidir com um Postgres, Mongo ou Redis que já exista na máquina.

Para parar: `docker compose -f infra/docker/compose.yaml down` (os dados ficam).
Para zerar tudo: acrescente `-v` (**apaga os dados**).

## Problemas comuns

- **`O Ledgerly Bank exige Java 25 ou superior`** — o `JAVA_HOME` aponta para outro JDK. Confira
  com `echo $JAVA_HOME` e `java -version`.
- **Porta já em uso** — outro programa usa a porta. Veja quem com `ss -ltnp | grep 15432` e troque
  a porta no `compose.yaml` (o lado esquerdo do `"127.0.0.1:15432:5432"`).
- **Mudei o script de `postgres/init` e nada aconteceu** — ele só roda com o volume vazio.
  Rode `down -v` e suba de novo (apaga os dados locais).
