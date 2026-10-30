# Kubernetes local do Ledgerly Bank

Os oito serviços rodando num Kubernetes de verdade, na sua máquina e de graça. Os bancos, o Kafka e o
Keycloak continuam no Docker Compose. As decisões estão na
[ADR-0038](../../docs/adr/0038-imagens-multi-stage-e-nativa.md) (imagens) e na
[ADR-0039](../../docs/adr/0039-kubernetes-local-com-k3d-e-helm.md) (cluster).

## As peças, em uma frase cada

| Termo | O que é |
|---|---|
| *Pod* | Um ou mais contêineres que nascem e morrem juntos. Pod é descartável: morreu, nasce outro. |
| *Deployment* | Mantém N pods no ar e troca a versão aos poucos. |
| *Service* | Um nome e um IP fixos na frente dos pods. Só entrega pedido para pod **pronto**. |
| *Probe* (sonda) | Pergunta periódica ao pod. *startup*: já subiu? *readiness*: pode receber pedido? *liveness*: travou? |
| *HPA* (*Horizontal Pod Autoscaler*) | Aumenta e diminui o número de cópias conforme o uso de CPU. |
| *PDB* (*Pod Disruption Budget*) | Quantas cópias podem sair de uma vez numa manutenção. |
| *Helm chart* | Modelo que gera todos esses objetos a partir de uma lista de valores. |
| *k3d* | O k3s, um Kubernetes enxuto, rodando dentro de um contêiner Docker. |

## Como usar

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # a infraestrutura
./scripts/servicos.sh parar                                # libera as portas 8080 e 8090
./scripts/imagens.sh construir                             # as imagens ledgerly/*:0.6.0
./scripts/k8s.sh subir                                     # 62 s do zero, medidos
./scripts/k8s.sh situacao                                  # pods, HPA, CPU e memória
./scripts/k8s.sh derrubar                                  # apaga o cluster; a infra do compose fica
```

Internet banking em <http://localhost:8080>, backoffice em <http://localhost:8090>. Para usar o kubectl:
`export KUBECONFIG=$PWD/.servicos/kubeconfig-k3d`.

Com a telemetria ([ADR-0042](../../docs/adr/0042-opentelemetry-e-otel-lgtm.md)): suba o perfil `observabilidade` do compose e
rode `LEDGERLY_OTEL=1 ./scripts/k8s.sh subir`. O chart usa amostragem de 10%.

Cada serviço tem uma ServiceAccount com o próprio nome, sem credencial montada. Aqui ela não dá permissão
nenhuma; na AWS, é o nome que o Pod Identity liga ao papel IAM do serviço.

Testado com k3d 5.9.0, Helm 4.3.1, kubectl 1.36.1 e k3s v1.36.5. **Recriou um contêiner do compose?** O
IP dele pode mudar: rode `./scripts/k8s.sh atualizar`, que relê os IPs.

## Como os pods alcançam o Compose

O nó do k3d entra na rede Docker `ledgerly_default`, então enxerga os contêineres por IP. Para o pod usar
um nome, o chart cria um *Service* sem seletor mais um *EndpointSlice* com o IP do contêiner. Assim
`postgres:5432` funciona dentro do pod, igual a qualquer outro Service.

O Service do Kafka **tem de se chamar `kafka`**: o broker responde aos clientes "me procure em
`kafka:9092`" (o *advertised listener* do compose), e o cliente passa a usar esse nome.

## O Keycloak e o endereço `localhost`

O token diz quem o emitiu: `iss=http://localhost:18081/realms/ledgerly`. Esse endereço precisa ser o
mesmo para o navegador e para os serviços, mas dentro de um pod `localhost` é o próprio pod. Dois ajustes
resolvem:

1. **`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` no compose.** O emissor e a tela de login continuam em
   `localhost:18081`; o token e as chaves (*back-channel*, a conversa de servidor para servidor) seguem
   o endereço de quem perguntou. Medido:

   | Campo da descoberta | Perguntando em `localhost:18081` | Perguntando em `keycloak:8080` |
   |---|---|---|
   | `issuer` | `localhost:18081` | `localhost:18081` |
   | `authorization_endpoint` | `localhost:18081` | `localhost:18081` |
   | `token_endpoint`, `jwks_uri` | `localhost:18081` | `keycloak:8080` |

2. **Um *sidecar* (contêiner auxiliar) nos gateways.** O gateway monta os endereços do Keycloak em código,
   a partir do emissor, sem variável que os troque. Um `socat` no pod encaminha `127.0.0.1:18081` para
   `keycloak:8080`. É um remendo para não mudar o Java; ver "O que falta" abaixo.

## Os números medidos

Máquina: 16 núcleos, um nó só, com a infraestrutura dividindo o mesmo computador. Servem para comparar
cenários entre si, não como capacidade de produção.

### Subida e memória de cada serviço

| Serviço | Subida (log do serviço) | Maior memória em `kubectl top` | *Request* de memória | *Limit* |
|---|---|---|---|---|
| razao | 8,9 s | 278 MiB | 340 MiB | 768 MiB |
| contas | 11,6 s | 325 MiB | 400 MiB | 768 MiB |
| extrato | 9,9 s | 249 MiB | 320 MiB | 768 MiB |
| assistente (sem Ollama) | 7,9 s | 191 MiB | 256 MiB | 768 MiB |
| antifraude | 4,7 s | 180 MiB | 256 MiB | 640 MiB |
| gateways | 7,4 s | 199 MiB | 256 MiB | 640 MiB |
| pix nativo | 0,102 s (0,09 a 0,16 s com a telemetria) | 60 MiB | 96 MiB | 256 MiB |

Os tempos são da subida simultânea de todos. O *request* é o maior uso com folga de 20% ou mais. A
memória do assistente não vale como teto: ele não respondeu a perguntas neste teste.

### HPA sob carga

Carga com o `fortio` de dentro do cluster: 800 pedidos/s no pix e 400 no razao por 150 s, só leitura, com
conexão nova a cada pedido para o Service sortear o pod.

| Rodada | Pix | Razao | O que aconteceu |
|---|---|---|---|
| 1. *Pool* de 20, token vencendo | 2 → 5 | 1 → 3 | 13,1% de 401 no pix: **erro do teste**, o token de 5 min era reaproveitado. |
| 2. *Pool* de 20, token novo | até 5 | até 3 | 0 de 401, mas **118 HTTP 500** no pix: "remaining connection slots are reserved". |
| 3. *Pool* de 6 (pix) e 8 (razao) | 2 → 5 | 1 → 3 | **180.000 pedidos, 0 erro**; no máximo 67 conexões no Postgres. |

Na rodada 3, as cópias voltaram a 2 e 1 cerca de 75 s depois do fim da carga. **Cada cópia abre o próprio
*pool* de conexões**: 5 cópias com 20 conexões pediam 100, e o Postgres só tem 97 livres. A conta está no
`values.yaml`; mudou o máximo de um HPA, refaça.

**O preço, não isolado:** o p99 (o tempo que 99% dos pedidos não ultrapassam) do razao subiu de cerca de
20 ms nas rodadas 1 e 2 para 99 ms na 3, e a média de 4 a 5 ms para 13 ms. O *pool* menor é o suspeito,
mas a rodada 3 também partiu de uma cópia só e fria. Não medi as duas causas em separado.

### Matar um pod no meio de um lote de Pix

Foram 320 Pix de 80 pagadores (no máximo 4 por pagador, abaixo da regra de rajada do antifraude). Depois
de 80 respostas, um pod do pix foi apagado com `--grace-period=0 --force`. O cliente repete com a mesma
*Idempotency-Key* até ter resposta.

| Medida | Resultado |
|---|---|
| Conexões cortadas pela morte do pod | 4 |
| Respostas 409 "pedido em andamento" nas novas tentativas | 116 |
| Pix que precisaram repetir | 4, respondidos na 31ª tentativa, cerca de 30 s depois |
| Situação final | **320 de 320 CONCLUIDA**, 320 ids diferentes |
| Saldo da recebedora | **R$ 3.200,00**, exatamente 320 × 10 |
| Pagadores com saldo errado | 0 de 80 |
| Conferência de integridade do razão | íntegro |

Os 30 s são a validade da trava do pedido e a reserva da Saga (`VALIDADE_DA_TRAVA` e `RESERVA`): nesse
tempo ninguém sabe se o dono da trava morreu. Depois, outra cópia retoma o Pix. **Quem chama precisa
insistir por mais de 30 s.** Na primeira tentativa, meu cliente desistiu antes, e a conferência parecia
mostrar dinheiro sumido; os saldos fecharam quando a Saga terminou.

### Prova ao contrário: tirar o que protege

Troca de versão do razao (`rollout restart`) sob 100 pedidos/s, 7.000 pedidos em cada linha.

| Configuração | Cópias | Erros |
|---|---|---|
| Sem *readiness* e sem *startup* | 3 | **586 (8,4%)** |
| Com as sondas | 1 | 13 (0,2%) |
| Com as sondas e `preStop` de 5 s | 1 | **0** |
| Com as sondas e `preStop` de 5 s | 3 | **0** |

Sem sonda, o pod recebe pedido enquanto a JVM ainda sobe. Os 13 erros restantes combinam com outra corrida:
o SIGTERM e a saída do pod da lista do Service acontecem ao mesmo tempo, e o `preStop` espera a lista
atualizar. Com ele, os erros sumiram; a causa é inferida desse resultado, não foi observada diretamente.
Foi assim que o `preStop` entrou no chart. As duas sondas
saíram juntas nesta prova, então ela não separa o efeito de cada uma. A comparação também não é de cópias
iguais: a linha sem sondas tinha 3, porque o HPA havia subido o razao.

Segunda prova: **HPA sem *requests***, com `--set servicos.razao.recursos=null`. O HPA ficou em
`cpu: <unknown>/60%`, com o aviso `missing request for cpu`, e as réplicas pararam em 3. O alvo é uma
porcentagem do *request*: sem ele, não há conta. As duas alterações foram desfeitas e conferidas.

## As telas contra o cluster

Os 7 testes de ponta a ponta do Playwright (`web/e2e`) passaram contra o cluster, com o Pix nativo, em
12,4 s: login nos dois gateways, Pix pela tela até o extrato, BOLA, fila de análise e os dois caminhos do Pix
retido. Os scripts dos testes falam direto com contas, razao e pix, por `kubectl port-forward`.

A primeira rodada deu 2 falhas, e nenhuma era do cluster: o teste lia o saldo enquanto a tela ainda mostrava
"…", e a fila de retidos, que traz os 50 mais antigos, estava cheia com os 5.305 da medição do circuito. O teste
foi corrigido, e a fila agora avisa quando pode haver mais.

Com `LEDGERLY_OTEL=1`, o Pix nativo enviou 18 rastros em cerca de 208 pedidos ao lgtm (amostragem de 10%).

## O que não foi verificado

- O assistente e a explicação do antifraude com o modelo de verdade: o Ollama estava desligado.
- O workflow `.github/workflows/imagens.yml` no GitHub: os comandos do chart rodaram aqui, mas o workflow
  ainda não rodou lá.
- Um `@QuarkusIntegrationTest` do nativo no build: a prova do nativo são os testes de ponta a ponta acima.
- A ordem dos eventos do contas durante a troca de versão, quando duas cópias publicam por instantes.
- Falha do nó, da rede ou da infraestrutura: só um pod do pix foi morto.

## O que falta para um ambiente de verdade

- **Gateway:** um endereço interno do Keycloak separado do emissor, no `ConfiguracaoDeLogin`, aposenta o
  `socat`. Exige mudança em Java.
- **Banco:** um *pooler* de conexões (PgBouncer, ou RDS Proxy na AWS), em vez de dividir 97 conexões à mão.
- **Segredos:** um cofre com o External Secrets. Aqui estão as senhas locais do compose, públicas de
  propósito.
- **Entrada:** um *Ingress* com TLS, no lugar do *NodePort*, e mais de um nó, porque com um só o PDB e as
  réplicas não protegem contra a queda da máquina.
