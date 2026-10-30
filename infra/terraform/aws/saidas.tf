# O que o chart Helm e o CI precisam saber depois de um apply. Nenhuma saída é senha: só o ARN
# do segredo, que o pod lê com o próprio papel IAM.

output "cluster" {
  description = "Nome e endereço da API do Kubernetes."
  value = {
    nome     = module.eks.nome
    endpoint = module.eks.endpoint
  }
}

output "vpc_id" {
  description = "Id da VPC."
  value       = module.rede.vpc_id
}

output "repositorios" {
  description = "Endereço do repositório ECR de cada imagem."
  value       = module.registro.enderecos
}

output "bancos" {
  description = "Endereço de cada RDS PostgreSQL."
  value       = module.bancos.enderecos
}

output "cache" {
  description = "Endereço do Valkey do pix."
  value       = module.cache.endereco
}

output "kafka" {
  description = "Endereços dos brokers do MSK (IAM, porta 9098)."
  value       = module.mensageria.enderecos_iam
}

output "documentos" {
  description = "Endereço do DocumentDB do extrato."
  value       = module.documentos.endereco
}

output "relatorios" {
  description = "Balde dos relatórios e a fila que avisa relatório novo."
  value = {
    balde = module.armazenamento.balde
    fila  = module.armazenamento.fila
  }
}

output "segredos" {
  description = "ARN de cada segredo (o valor nunca sai do Secrets Manager)."
  value = merge(
    module.segredos.arns,
    { for s, arn in module.bancos.segredos : "banco/${s}" => arn },
    { "cache/token" = module.cache.segredo, "documentos/mestre" = module.documentos.segredo },
  )
}

output "papeis" {
  description = "Papel IAM de cada serviço (para a ServiceAccount do Kubernetes)."
  value       = module.identidades.papeis
}

output "grupos_de_seguranca" {
  description = "Grupo de segurança de cada serviço (para a SecurityGroupPolicy do Kubernetes)."
  value       = module.seguranca.sg_servicos
}
