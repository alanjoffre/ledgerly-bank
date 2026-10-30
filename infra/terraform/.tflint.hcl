# Configuração do tflint, o "corretor ortográfico" do Terraform: acha erro que o `validate` não
# acha (tipo de máquina que não existe, variável sem tipo, nome fora do padrão).
# Rode com: scripts/nuvem.sh validar

config {
  # Também confere o que está dentro de cada módulo, não só a raiz.
  call_module_type = "local"
}

# Regras da própria linguagem (variável sem descrição, código sem uso etc.).
plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

# Regras da AWS: tipo de instância inválido, versão de motor inexistente e afins.
# Versão fixa: a mesma regra precisa dar o mesmo resultado hoje e daqui a um ano.
plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
