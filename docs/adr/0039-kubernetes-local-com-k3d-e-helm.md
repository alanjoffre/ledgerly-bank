# ADR-0039: Kubernetes local com k3d e um Helm chart, usando a infraestrutura do Compose

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

O projeto precisa mostrar os serviços num orquestrador, com escala automática, troca de versão sem queda
e recuperação de falhas. A nuvem não é opção, porque o custo tem de ser zero
([ADR-0006](0006-nuvem-com-custo-zero.md)). A máquina tem memória limitada, e os serviços não podem ter o
Java alterado só para caber no cluster.

## Decisão

**Um cluster k3d de um nó, na rede Docker do Compose, com um Helm chart em `infra/k8s/ledgerly/`:**

- **Um modelo para todos:** Deployment, Service, ConfigMap e Secret saem de um mapa `servicos` no
  `values.yaml`, sem cópia e cola. É mapa, não lista, porque o Helm junta mapas e substitui listas.
- **Sondas nos endereços certos:** `/actuator/health/*` no Spring e `/q/health/*` no Quarkus.
- **Recursos medidos,** com o maior uso em `kubectl top` mais folga.
- **HPA no pix e no razao, PDB em todos, e segurança restrita:** sem root, sistema de arquivos só de
  leitura, `drop ALL` e sem a credencial da API montada.
- **`preStop` de 5 s,** com o `sleep` nativo, que dispensa shell na imagem.
- **A infraestrutura fica no Compose:** cada peça vira um Service sem seletor com um EndpointSlice.
- **Keycloak:** `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` no compose e um *sidecar* `socat` nos gateways.
- **Pix nativo no k3d:** é o serviço que o HPA mais multiplica.

## Alternativas consideradas

- **minikube ou kind:** também servem. O k3d é o mais leve aqui, e o cluster sai do zero junto com o chart
  em 62 s. Não fiz medição comparativa dos três.
- **Infraestrutura dentro do cluster:** mais realista, porém gasta mais memória, duplica o que o Compose
  já faz e obrigaria a mudar os testes e os scripts locais.
- **Kustomize:** sem modelos, um serviço novo vira vários arquivos; o Helm gera tudo de uma lista.
- **Mudar o Java do gateway:** é o certo a longo prazo, mas estava fora do escopo. Por isso o `socat`.
- **`--set` de endereços em vez de EndpointSlice:** o Kafka devolve o nome `kafka:9092` ao cliente, então
  o nome precisa resolver dentro do pod de qualquer jeito.

## Consequências

As medições completas estão no [README](../../infra/k8s/README.md).

- **Positivas:**
  - Sob carga, o pix foi de 2 para 5 cópias e o razao de 1 para 3, com 180.000 pedidos e nenhum erro.
  - Com um pod morto no meio de 320 Pix, os 320 concluíram, sem Pix perdido nem em dobro.
  - A troca de versão sob carga foi de 586 erros (sem sondas) para 0 (com sondas e `preStop`).
  - O teste achou um limite que o ambiente local escondia: 5 cópias × 20 conexões estouram o Postgres.
- **Negativas:**
  - **O total de conexões é uma conta manual:** `soma(máximo de cópias × pool) < 97`, escrita no
    `values.yaml`. O *pool* menor pode ter piorado o p99 do razao, de cerca de 20 ms para 99 ms no pico;
    não medi essa causa isolada.
  - **Depois da morte de um pod, o pedido em andamento responde 409 por cerca de 30 s,** e o cliente
    precisa insistir. É a validade da trava e da reserva da Saga; o dinheiro está certo, mas a espera é real.
  - **O IP dos contêineres é lido na instalação:** recriar um deles exige `./scripts/k8s.sh atualizar`.
  - **O `socat` é um remendo,** e um contêiner a mais por gateway.
  - **Um nó só não protege da queda da máquina;** réplicas e PDB aqui demonstram o mecanismo.
