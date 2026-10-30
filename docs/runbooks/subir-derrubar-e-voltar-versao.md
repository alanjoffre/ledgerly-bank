# Subir, derrubar e voltar versão

## Na máquina, com processos Java

```bash
docker compose -f infra/docker/compose.yaml up -d --wait   # a infraestrutura
./mvnw -DskipTests package                                 # os pacotes, se o código mudou
./scripts/servicos.sh subir                                # os oito serviços
./scripts/semear.sh                                        # os clientes de demonstração
./scripts/servicos.sh situacao
```

Os logs ficam em `.servicos/<serviço>.log`. Para reiniciar um só, ou parar tudo:

```bash
./scripts/servicos.sh parar razao
./scripts/servicos.sh subir razao
./scripts/servicos.sh parar
docker compose -f infra/docker/compose.yaml down           # sem -v: os dados ficam nos volumes
```

**Não use `down -v` sem querer perder os dados:** apaga os bancos e o tópico do Kafka, que é de onde
o extrato se reconstrói.

## No Kubernetes local

Os serviços da máquina precisam estar parados, porque os gateways do cluster usam as mesmas portas.

```bash
./scripts/imagens.sh construir       # a nativa leva uns 4 minutos
./scripts/k8s.sh subir
./scripts/k8s.sh situacao
./scripts/k8s.sh derrubar            # devolve a memória; a infraestrutura do compose fica
```

## Voltar uma versão

Cada `helm upgrade` guarda uma revisão, e voltar é trocar os pods um a um, sem parar o serviço.

```bash
export KUBECONFIG=$PWD/.servicos/kubeconfig-k3d
helm history ledgerly -n ledgerly
helm rollback ledgerly <revisão> -n ledgerly --wait --timeout 6m
kubectl get pods -n ledgerly
```

**Resolveu quando** todos os pods estão `Running`, prontos, e o Pix de teste conclui.

**O rollback não desfaz migração de banco.** O Flyway só anda para a frente. Por isso uma migração
precisa funcionar também com a versão anterior do serviço (primeiro adiciona a coluna, e só em outra
versão remove a velha). Migração que quebra a versão anterior não se volta com este roteiro.

## Ensaiado

No k3d, com as imagens da versão 0.6.0, publiquei uma versão com uma imagem que não existe. O `helm upgrade` falhou
depois dos 90 s de limite (revisão 2, `failed`). Os pods novos ficaram em `ErrImageNeverPull`, e os antigos seguiram
atendendo. O `helm rollback ledgerly 1` voltou em 2 s, como revisão 3, e o Pix de teste respondeu `201`, `CONCLUIDA`.

Foi rápido porque os pods antigos nunca chegaram a sair. **A volta de uma versão que subiu e só depois deu problema
não foi ensaiada**: nela, os pods são trocados de novo, e leva mais tempo.

## Em produção

O Terraform da AWS em `infra/terraform/` nunca foi aplicado ([ADR-0040](../adr/0040-terraform-validado-e-simulador-local.md)).
Faltariam imagem assinada em registro, implantação por etapas, e bancos gerenciados com cópia de
segurança, cuja restauração precisaria de um roteiro próprio, ensaiado.
