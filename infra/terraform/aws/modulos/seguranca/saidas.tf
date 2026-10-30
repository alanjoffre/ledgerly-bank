output "sg_servicos" {
  description = "Grupo de segurança de cada serviço (mapa serviço => id)."
  value       = { for s, g in aws_security_group.servico : s => g.id }
}

output "sg_entrada" {
  description = "Grupo de segurança do balanceador de carga público."
  value       = aws_security_group.entrada.id
}
