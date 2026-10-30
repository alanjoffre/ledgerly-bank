# Módulo eks: o cluster Kubernetes gerenciado e o grupo de nós.
#
# EKS (*Elastic Kubernetes Service*) é o Kubernetes da AWS: ela opera o plano de controle (a API
# e o etcd, em três zonas) e cobra por hora de cluster; os nós são máquinas EC2 nossas, num
# grupo gerenciado (*managed node group*) que a AWS atualiza uma a uma, sem derrubar tudo.
#
# Escolhas de segurança, cada uma com o porquê:
# - Segredos do Kubernetes cifrados com a nossa chave KMS (*envelope encryption*): sem isso, um
#   backup do etcd mostraria as senhas em texto.
# - Acesso à API pelo modo "API" (*access entries*), e não pelo antigo ConfigMap aws-auth, que
#   uma edição errada trancava o cluster para todo mundo.
# - API pública só para as faixas de IP da equipe; os nós falam com ela por dentro da VPC.
# - Os cinco registros do plano de controle ligados (inclusive "audit": quem fez o quê).

resource "aws_cloudwatch_log_group" "cluster" {
  # O nome é fixo pela AWS: /aws/eks/<cluster>/cluster. Criar antes permite escolher a retenção.
  name              = "/aws/eks/${var.nome}/cluster"
  retention_in_days = var.dias_retencao_logs
}

resource "aws_iam_role" "cluster" {
  name = "${var.nome}-eks-cluster"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy",
    # Necessária para Security Groups for Pods: o EKS cria as placas de rede dos pods.
    "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController",
  ])
  role       = aws_iam_role.cluster.name
  policy_arn = each.key
}

# A política de chave padrão delega ao IAM: o papel do cluster precisa de permissão explícita
# para usar a chave dos segredos, e só dessa chave.
resource "aws_iam_role_policy" "cluster_kms" {
  name = "usar-chave-dos-segredos"
  role = aws_iam_role.cluster.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kms:Encrypt", "kms:Decrypt", "kms:DescribeKey", "kms:ListGrants", "kms:CreateGrant"]
      Resource = var.chave_kms_arn
    }]
  })
}

resource "aws_eks_cluster" "principal" {
  name     = var.nome
  version  = var.versao_kubernetes
  role_arn = aws_iam_role.cluster.arn

  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  encryption_config {
    resources = ["secrets"]
    provider {
      key_arn = var.chave_kms_arn
    }
  }

  vpc_config {
    subnet_ids              = var.subredes
    endpoint_private_access = true
    endpoint_public_access  = length(var.cidrs_acesso_api) > 0
    public_access_cidrs     = length(var.cidrs_acesso_api) > 0 ? var.cidrs_acesso_api : null
  }

  # Sem suporte estendido (pago à parte): o cluster atualiza no fim do suporte padrão.
  upgrade_policy {
    support_type = "STANDARD"
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_iam_role_policy.cluster_kms,
    aws_cloudwatch_log_group.cluster,
  ]
}

# Os nós ----------------------------------------------------------------------------------------

resource "aws_iam_role" "nos" {
  name = "${var.nome}-eks-nos"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "nos" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
  ])
  role       = aws_iam_role.nos.name
  policy_arn = each.key
}

resource "aws_eks_node_group" "geral" {
  cluster_name    = aws_eks_cluster.principal.name
  node_group_name = "geral"
  node_role_arn   = aws_iam_role.nos.arn
  subnet_ids      = var.subredes
  version         = var.versao_kubernetes

  ami_type       = "AL2023_x86_64_STANDARD"
  capacity_type  = "ON_DEMAND"
  instance_types = var.tipos_de_no
  disk_size      = 50

  scaling_config {
    min_size     = var.nos_minimo
    desired_size = var.nos_minimo
    max_size     = var.nos_maximo
  }

  # Atualização um nó por vez: os pods saem com calma (respeitando o PodDisruptionBudget).
  update_config {
    max_unavailable = 1
  }

  # O número de nós muda pelo autoescalador (Karpenter ou Cluster Autoscaler), não pelo Terraform.
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.nos]
}

# Complementos (*add-ons*) ------------------------------------------------------------------------
# Sem addon_version, a AWS instala a versão padrão para a versão do Kubernetes; quem fixa a versão
# exata é o `terraform plan` de antes de um apply real (aws eks describe-addon-versions).

resource "aws_eks_addon" "rede_vpc" {
  cluster_name = aws_eks_cluster.principal.name
  addon_name   = "vpc-cni"
  # ENABLE_POD_ENI liga Security Groups for Pods (o grupo de cada serviço, no módulo seguranca).
  configuration_values = jsonencode({
    env = {
      ENABLE_POD_ENI                    = "true"
      POD_SECURITY_GROUP_ENFORCING_MODE = "standard"
    }
  })
}

resource "aws_eks_addon" "dns" {
  cluster_name = aws_eks_cluster.principal.name
  addon_name   = "coredns"
  depends_on   = [aws_eks_node_group.geral]
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name = aws_eks_cluster.principal.name
  addon_name   = "kube-proxy"
}

# EKS Pod Identity: cada serviço recebe um papel IAM próprio (módulo identidades), sem chave de
# acesso guardada em lugar nenhum. O agente troca o token do pod por credenciais temporárias.
resource "aws_eks_addon" "identidade_dos_pods" {
  cluster_name = aws_eks_cluster.principal.name
  addon_name   = "eks-pod-identity-agent"
}

# Quem administra o cluster: só os papéis listados, e com a política de administrador do EKS.
resource "aws_eks_access_entry" "administradores" {
  for_each      = toset(var.papeis_administradores)
  cluster_name  = aws_eks_cluster.principal.name
  principal_arn = each.key
}

resource "aws_eks_access_policy_association" "administradores" {
  for_each      = toset(var.papeis_administradores)
  cluster_name  = aws_eks_cluster.principal.name
  principal_arn = each.key
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.administradores]
}
