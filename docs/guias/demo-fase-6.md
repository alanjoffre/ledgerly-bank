# Guia: demonstração da Fase 6

Mostra, na sua máquina, o banco como se opera um sistema de verdade:

- as **imagens**: mudar uma linha de código troca uma camada pequena, e o Pix nativo sobe em ~0,1 s;
- o **Kubernetes** local: escala sob carga, troca de versão sem erro, e um pod morto no meio de um lote
  de Pix sem perder dinheiro;
- o **Terraform** da AWS conferido sem conta na AWS e sem custo;
- o **circuit breaker**: o antifraude trava, e o Pix deixa de esperar por ele;
- os **rastros e métricas**: o caminho de um Pix pelos serviços, e o circuito abrindo no painel.

Faça só a parte que interessa; a do Kubernetes precisa das imagens da parte 1. Os números medidos estão nas decisões:
[ADR-0038](../adr/0038-imagens-multi-stage-e-nativa.md) (imagens),
[ADR-0039](../adr/0039-kubernetes-local-com-k3d-e-helm.md) (Kubernetes),
[ADR-0040](../adr/0040-terraform-validado-e-simulador-local.md) (Terraform),
[ADR-0041](../adr/0041-circuit-breaker-no-antifraude.md) (circuito) e
[ADR-0042](../adr/0042-opentelemetry-e-otel-lgtm.md) (telemetria).

## 1. As imagens

Só o Docker é necessário: o Java e o Node ficam dentro dos estágios do `Dockerfile`.

```bash
./scripts/imagens.sh construir        # todas; a nativa é a última e a mais cara (uns 4 minutos)
./scripts/imagens.sh tamanhos
```

## 2. O Kubernetes local

Precisa de `k3d`, `helm` e `kubectl` no `PATH`, e dos serviços locais parados: os BFFs do cluster usam as
portas 8080 e 8090.

```bash
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml up -d --wait
./scripts/k8s.sh subir                # 62 s do zero, medidos
./scripts/k8s.sh situacao             # pods, HPA, CPU e memória
export KUBECONFIG=$PWD/.servicos/kubeconfig-k3d
```

Os experimentos (carga no HPA, matar um pod, tirar as sondas) e os resultados estão em
[infra/k8s/README.md](../../infra/k8s/README.md). Ao terminar: `./scripts/k8s.sh derrubar`.

## 3. O Terraform, sem conta na AWS

Precisa de Terraform 1.17.1, Docker e aws-cli (o tflint e o trivy rodam em contêiner). Não usa credencial de verdade e não cria nada na AWS.

```bash
./scripts/nuvem.sh validar            # formatação, validate, plan, regras do projeto, tflint e trivy: cerca de 30 s
./scripts/nuvem.sh simular            # aplica a parte local no Moto, confere e destrói: cerca de 4 min
```

O limite está na [ADR-0040](../adr/0040-terraform-validado-e-simulador-local.md): foi validado e simulado,
nunca aplicado numa conta real.

## 4. O circuit breaker

```bash
docker compose -f infra/docker/compose.yaml up -d --wait
./mvnw package -DskipTests
./scripts/servicos.sh subir razao contas antifraude pix
./scripts/semear.sh
desempenho/circuito/medir.sh com-circuito
desempenho/circuito/medir.sh sem-circuito   # a prova ao contrário: o mesmo pacote, com o circuito desligado
```

O script congela o antifraude por 40 s e imprime o Pix por segundo, os tempos e quando a nota volta. O
resumo vai para `desempenho/resultados/circuito/`.

## 5. Os rastros e o painel

```bash
docker compose -f infra/docker/compose.yaml --profile observabilidade up -d --wait
./scripts/servicos.sh parar
LEDGERLY_OTEL=1 ./scripts/servicos.sh subir razao contas antifraude pix
LEDGERLY_OTEL=1 desempenho/circuito/medir.sh com-circuito
```

Abra http://localhost:13000:

- **Dashboards → Ledgerly Bank → Pix e circuito do antifraude**: o circuito do antifraude abre durante os 40 s da trava e fecha
  depois.
- **Explore → Tempo**: procure `{resource.service.name="razao"}` e abra um rastro. Um único `traceId`
  atravessa pix, contas, antifraude e razão.

Sem o `LEDGERLY_OTEL=1`, nada é exportado, e nada disso precisa estar no ar.

## 6. Desligar

```bash
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml --profile observabilidade down   # os dados ficam; -v apaga tudo
```
