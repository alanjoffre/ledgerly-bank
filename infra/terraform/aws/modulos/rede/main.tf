# Módulo rede: a VPC do banco, em três camadas de sub-rede por zona.
#
# VPC (*Virtual Private Cloud*) é a rede privada do banco dentro da AWS. Ela é dividida em
# sub-redes, uma de cada camada em cada zona de disponibilidade (um prédio separado da AWS na
# mesma região), para o banco continuar no ar se um prédio cair:
#
#   pública   → só o balanceador de carga (a porta de entrada) e o NAT. Rota para a internet.
#   aplicação → os nós do Kubernetes e os pods. Saem para a internet só pelo NAT (baixar imagem,
#               falar com APIs da AWS); ninguém de fora entra. Sub-redes grandes (/20): no EKS
#               cada pod ganha um IP da VPC, e IP acaba antes de CPU.
#   dados     → bancos, cache, Kafka. NENHUMA rota para a internet, nem de saída.
#
# NAT (*Network Address Translation*) é o "porteiro" que deixa a sub-rede privada sair sem ser
# alcançada de fora. Custa por hora e por GB: um só NAT é mais barato; um por zona evita que a
# queda de uma zona corte a saída das outras.

resource "aws_vpc" "principal" {
  cidr_block           = var.cidr_vpc
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.nome }
}

# O grupo de segurança padrão da VPC nasce aberto entre si. Sem regras, ninguém o usa por engano.
resource "aws_default_security_group" "fechado" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = "${var.nome}-padrao-fechado" }
}

resource "aws_internet_gateway" "principal" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = var.nome }
}

resource "aws_subnet" "publica" {
  count = length(var.zonas)

  vpc_id            = aws_vpc.principal.id
  availability_zone = var.zonas[count.index]
  cidr_block        = cidrsubnet(var.cidr_vpc, 8, count.index)
  # Nada recebe IP público automaticamente: o balanceador e o NAT recebem o seu de propósito.
  map_public_ip_on_launch = false

  tags = {
    Name                     = "${var.nome}-publica-${var.zonas[count.index]}"
    Camada                   = "publica"
    "kubernetes.io/role/elb" = "1"
  }
}

resource "aws_subnet" "aplicacao" {
  count = length(var.zonas)

  vpc_id            = aws_vpc.principal.id
  availability_zone = var.zonas[count.index]
  cidr_block        = cidrsubnet(var.cidr_vpc, 4, count.index + 1)

  tags = {
    Name                              = "${var.nome}-aplicacao-${var.zonas[count.index]}"
    Camada                            = "aplicacao"
    "kubernetes.io/role/internal-elb" = "1"
  }
}

resource "aws_subnet" "dados" {
  count = length(var.zonas)

  vpc_id            = aws_vpc.principal.id
  availability_zone = var.zonas[count.index]
  cidr_block        = cidrsubnet(var.cidr_vpc, 8, 200 + count.index)

  tags = {
    Name   = "${var.nome}-dados-${var.zonas[count.index]}"
    Camada = "dados"
  }
}

resource "aws_eip" "nat" {
  count  = var.nat_por_zona ? length(var.zonas) : 1
  domain = "vpc"
  tags   = { Name = "${var.nome}-nat-${count.index}" }
}

resource "aws_nat_gateway" "saida" {
  count = var.nat_por_zona ? length(var.zonas) : 1

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.publica[count.index].id
  tags          = { Name = "${var.nome}-nat-${var.zonas[count.index]}" }

  depends_on = [aws_internet_gateway.principal]
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.principal.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.principal.id
  }

  tags = { Name = "${var.nome}-publica" }
}

resource "aws_route_table_association" "publica" {
  count          = length(var.zonas)
  subnet_id      = aws_subnet.publica[count.index].id
  route_table_id = aws_route_table.publica.id
}

# Uma tabela por zona: com um NAT por zona, cada uma sai pelo NAT do próprio prédio.
resource "aws_route_table" "aplicacao" {
  count  = length(var.zonas)
  vpc_id = aws_vpc.principal.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.saida[var.nat_por_zona ? count.index : 0].id
  }

  tags = { Name = "${var.nome}-aplicacao-${var.zonas[count.index]}" }
}

resource "aws_route_table_association" "aplicacao" {
  count          = length(var.zonas)
  subnet_id      = aws_subnet.aplicacao[count.index].id
  route_table_id = aws_route_table.aplicacao[count.index].id
}

# Sem rota para fora: um banco de dados invadido não tem por onde mandar os dados embora.
resource "aws_route_table" "dados" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = "${var.nome}-dados" }
}

resource "aws_route_table_association" "dados" {
  count          = length(var.zonas)
  subnet_id      = aws_subnet.dados[count.index].id
  route_table_id = aws_route_table.dados.id
}

# Ponto de acesso ao S3 por dentro da AWS (*gateway endpoint*): de graça, e o tráfego para o S3
# (relatórios, camadas das imagens do ECR) não passa pelo NAT, que cobra por GB.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.principal.id
  service_name      = "com.amazonaws.${var.regiao}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.aplicacao[*].id

  tags = { Name = "${var.nome}-s3" }
}

# Registro de fluxo (*flow logs*): quem falou com quem, aceito ou recusado. É o que a perícia pede
# depois de um incidente, e o que mostra se um grupo de segurança está barrando o que devia.
resource "aws_cloudwatch_log_group" "fluxo" {
  name              = "/ledgerly/${var.nome}/rede/fluxo"
  retention_in_days = var.dias_retencao_logs
}

resource "aws_iam_role" "fluxo" {
  name = "${var.nome}-registro-de-fluxo"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "fluxo" {
  name = "escrever-registro-de-fluxo"
  role = aws_iam_role.fluxo.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = "${aws_cloudwatch_log_group.fluxo.arn}:*"
    }]
  })
}

resource "aws_flow_log" "vpc" {
  vpc_id          = aws_vpc.principal.id
  traffic_type    = "ALL"
  log_destination = aws_cloudwatch_log_group.fluxo.arn
  iam_role_arn    = aws_iam_role.fluxo.arn
}
