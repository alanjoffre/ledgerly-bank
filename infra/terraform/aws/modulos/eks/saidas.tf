output "nome" {
  description = "Nome do cluster."
  value       = aws_eks_cluster.principal.name
}

output "endpoint" {
  description = "Endereço da API do Kubernetes."
  value       = aws_eks_cluster.principal.endpoint
}

output "sg_cluster" {
  description = "Grupo de segurança que o EKS cria para o cluster e põe nos nós."
  value       = aws_eks_cluster.principal.vpc_config[0].cluster_security_group_id
}
