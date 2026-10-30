output "vpc_id" {
  description = "Id da VPC."
  value       = aws_vpc.principal.id
}

output "cidr_vpc" {
  description = "Faixa de IPs da VPC."
  value       = aws_vpc.principal.cidr_block
}

output "subredes_publicas" {
  description = "Sub-redes públicas (balanceador de carga e NAT)."
  value       = aws_subnet.publica[*].id
}

output "subredes_aplicacao" {
  description = "Sub-redes da aplicação (nós e pods do Kubernetes)."
  value       = aws_subnet.aplicacao[*].id
}

output "subredes_dados" {
  description = "Sub-redes dos dados (sem rota para a internet)."
  value       = aws_subnet.dados[*].id
}
