# Módulo seguranca: um grupo de segurança por serviço, e só as conversas que a arquitetura tem.
#
# Grupo de segurança (*security group*) é um firewall que a AWS aplica em cada placa de rede:
# por padrão nega tudo que entra, e cada regra abre uma porta para uma origem. Aqui a origem
# nunca é uma faixa de IPs, e sim o GRUPO do outro serviço: "o pix pode falar com o antifraude"
# continua verdade quando os pods mudam de IP, e nenhum outro pod herda a permissão.
#
# Como um pod ganha o grupo do seu serviço: o EKS tem *Security Groups for Pods* (o plugin de
# rede da VPC, com ENABLE_POD_ENI ligado no módulo eks). O lado Kubernetes é um objeto
# SecurityGroupPolicy por serviço, que ainda não existe no chart (ver infra/terraform/README.md).
#
# A mesma ideia vale para os dados: o grupo do banco do razão aceita a porta 5432 só do grupo do
# razão (módulo bancos), o Redis só do pix (módulo cache), e assim por diante.

resource "aws_security_group" "servico" {
  for_each = toset(var.servicos)

  name        = "${var.nome}-${each.key}"
  description = "Pods do servico ${each.key} (Security Groups for Pods)"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.nome}-${each.key}", Servico = each.key }
}

# Quem chama quem, como na visão geral da arquitetura (docs/arquitetura/visao-geral.md).
locals {
  chamadas = flatten([
    for origem, destinos in var.chamadas : [
      for destino in destinos : [
        for porta in var.portas[destino] : { origem = origem, destino = destino, porta = porta }
      ]
    ]
  ])

  sondas = flatten([
    for servico, portas in var.portas : [for porta in portas : { servico = servico, porta = porta }]
  ])

  entradas_publicas = flatten([
    for servico in var.servicos_publicos : [for porta in var.portas[servico] : { servico = servico, porta = porta }]
  ])
}

resource "aws_vpc_security_group_ingress_rule" "chamada" {
  for_each = { for c in local.chamadas : "${c.origem}-para-${c.destino}-${c.porta}" => c }

  security_group_id            = aws_security_group.servico[each.value.destino].id
  referenced_security_group_id = aws_security_group.servico[each.value.origem].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.porta
  to_port                      = each.value.porta
  description                  = "${each.value.origem} chama ${each.value.destino}"
}

# O kubelet (o agente do Kubernetes em cada nó) confere se o pod está vivo (*probes*). Ele fala
# a partir do grupo do cluster, que o EKS cria e põe nos nós.
resource "aws_vpc_security_group_ingress_rule" "sondas" {
  for_each = { for s in local.sondas : "${s.servico}-${s.porta}" => s }

  security_group_id            = aws_security_group.servico[each.value.servico].id
  referenced_security_group_id = var.sg_cluster
  ip_protocol                  = "tcp"
  from_port                    = each.value.porta
  to_port                      = each.value.porta
  description                  = "sondas do kubelet (liveness e readiness)"
}

# Saída: para dentro da VPC (outros pods, bancos, DNS do cluster) e HTTPS para fora (APIs da AWS
# como STS e Secrets Manager, pelo NAT). Quem bloqueia o banco é a ENTRADA do grupo do banco.
resource "aws_vpc_security_group_egress_rule" "vpc" {
  for_each = aws_security_group.servico

  security_group_id = each.value.id
  cidr_ipv4         = var.cidr_vpc
  ip_protocol       = "-1"
  description       = "dentro da VPC"
}

resource "aws_vpc_security_group_egress_rule" "https" {
  for_each = aws_security_group.servico

  security_group_id = each.value.id
  # As APIs da AWS (STS, Secrets Manager) ficam fora da VPC: a saída é só a porta 443.
  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "tcp"
  from_port   = 443
  to_port     = 443
  description = "HTTPS para as APIs da AWS"
}

# A porta de entrada: o balanceador de carga (ALB) que o AWS Load Balancer Controller cria a
# partir do Ingress do Kubernetes. É o único ponto aberto para a internet, e só em HTTPS.
resource "aws_security_group" "entrada" {
  name        = "${var.nome}-entrada"
  description = "Balanceador de carga publico (somente HTTPS)"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.nome}-entrada" }
}

resource "aws_vpc_security_group_ingress_rule" "entrada_https" {
  for_each = toset(var.cidrs_entrada)

  security_group_id = aws_security_group.entrada.id
  # O internet banking é público por definição; só a 443 abre. É a única entrada que a
  # conferência do plano aceita (infra/terraform/validacao/conferir_plano.py).
  cidr_ipv4   = each.key
  ip_protocol = "tcp"
  from_port   = 443
  to_port     = 443
  description = "HTTPS dos clientes"
}

resource "aws_vpc_security_group_egress_rule" "entrada_para_publicos" {
  for_each = { for e in local.entradas_publicas : "${e.servico}-${e.porta}" => e }

  security_group_id            = aws_security_group.entrada.id
  referenced_security_group_id = aws_security_group.servico[each.value.servico].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.porta
  to_port                      = each.value.porta
  description                  = "balanceador para ${each.value.servico}"
}

resource "aws_vpc_security_group_ingress_rule" "publicos_da_entrada" {
  for_each = { for e in local.entradas_publicas : "${e.servico}-${e.porta}" => e }

  security_group_id            = aws_security_group.servico[each.value.servico].id
  referenced_security_group_id = aws_security_group.entrada.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.porta
  to_port                      = each.value.porta
  description                  = "vem do balanceador"
}
