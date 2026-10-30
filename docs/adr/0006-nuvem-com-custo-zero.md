# ADR-0006: Nuvem com custo zero — Terraform validado, sem aplicar

- **Status:** Aceita. O simulador mudou: o LocalStack passou a exigir token, e o projeto usa o Moto
  ([ADR-0040](0040-terraform-validado-e-simulador-local.md)).
- **Data:** 2026-10-26

## Contexto

O projeto precisa mostrar infraestrutura na AWS (EKS, RDS, ElastiCache, MSK). Mas manter isso no
ar custa caro: só o plano de controle do EKS fica em torno de US$ 70/mês, e RDS e MSK somam mais.
Para um projeto de estudo, pagar mensalidade não se justifica.

## Decisão

- A infraestrutura AWS é escrita como código em **Terraform** e **validada no CI**
  (`terraform fmt`, `validate` e `plan`), mas **não é aplicada** (`apply`) numa conta real.
- O comportamento é demonstrado localmente: **k3d** (Kubernetes dentro do Docker) para os
  serviços e **LocalStack** (simulador da AWS) para S3, SQS e Secrets Manager.

## Alternativas consideradas

- **Subir na AWS e deixar no ar** — demonstração pública permanente, por ~US$ 100+/mês.
- **Subir por algumas horas para gravar a demonstração e destruir** — continua possível no futuro,
  com uma nova ADR, sem mudar o código Terraform.

## Consequências

- **Positivas:** custo zero; quem clona o projeto roda tudo sem conta na AWS.
- **Negativas:** o LocalStack não reproduz 100% da AWS (permissões IAM, limites, latência). A
  documentação deixa claro o que foi validado de verdade e o que foi simulado.
