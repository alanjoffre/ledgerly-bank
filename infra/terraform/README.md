# Terraform da AWS do Ledgerly Bank

Toda a infraestrutura do banco na AWS, escrita como código. **Ela nunca foi aplicada numa conta
real, e isso é de propósito** (custo zero, [ADR-0006](../../docs/adr/0006-nuvem-com-custo-zero.md)
e [ADR-0040](../../docs/adr/0040-terraform-validado-e-simulador-local.md)). Ligada, custaria cerca
de **US$ 2.750 por mês**.

*Terraform* é uma ferramenta em que você descreve a infraestrutura em arquivos de texto, e ela
calcula e executa o que falta criar. O `plan` mostra o que faria; o `apply` faz.

## Como rodar

Precisa de Terraform 1.17.1, Docker e aws-cli. Não precisa de conta na AWS.

```bash
./scripts/nuvem.sh validar       # cerca de 30 s: formato, validate, plan sem conta, regras, tflint, trivy
./scripts/nuvem.sh simular       # cerca de 4 min: cria, confere e destrói no simulador (Moto)
./scripts/nuvem.sh simular-tudo  # experimento: tenta criar TUDO no simulador
```

O CI roda os dois primeiros a cada mudança nesta pasta (`.github/workflows/nuvem.yml`).

## O que foi validado de verdade, e o que não foi

Esta é a parte mais importante deste documento. Medido em 30/10/2026.

| Afirmação | Tipo | Como |
|---|---|---|
| O código é sintaticamente correto e os tipos batem | **validado** | `terraform validate` |
| O plano cria **230 recursos** sem erro | **validado** | `terraform plan` com credencial falsa |
| A internet só entra pelo balanceador, na porta 443 | **validado** | `validacao/conferir_plano.py`, sobre o plano |
| Nenhuma falha de severidade média ou maior | **validado** | trivy 0.75.0 (4 achados baixos, abaixo) |
| Os tipos de máquina existem em São Paulo | **validado** | constam da tabela oficial de preços de `sa-east-1` |
| Balde, filas e segredos são criados, funcionam e são destruídos | **simulado** | `simular`, no Moto: 19 recursos |
| Relatório no S3 vira aviso na fila, e versão travada não se apaga | **simulado** | `simular`, conferido com o aws-cli |
| A senha gerada não aparece no estado do Terraform | **simulado** | `simular` lê a senha no simulador e a procura no estado |
| A AWS aceita este código | **não verificado** | só um `apply` real diria |
| As permissões IAM e KMS bastam, e nada a mais | **não verificado** | o simulador não as avalia como a AWS |
| As combinações de versão com tipo de máquina são aceitas | **não verificado** | só um `apply` real diria |
| Os serviços Java rodam nesta infraestrutura | **não verificado** | ver "O que falta" |

O simulador **imita a API, não as regras da AWS**. Ele aceitar um pedido não prova que a AWS
aceitaria.

### Prova de que a validação pega erro

Cada defeito abaixo foi colocado de propósito numa cópia do código, e o `validar` foi rodado.

| Defeito | Quem pegou |
|---|---|
| Banco na porta 5432 aberto para `0.0.0.0/0` | regras do projeto (6 violações). O trivy **não** pega |
| Banco sem cifra (`storage_encrypted = false`) | trivy, regra AWS-0080, nos 6 bancos |
| API do Kubernetes aberta ao mundo | a `validation` da variável, no `plan` |
| Tipo de cache que não existe | tflint (`aws_elasticache_replication_group_invalid_type`) |
| Código fora do formato | `terraform fmt -check` |

Dois pontos cegos apareceram nesse teste:

- **trivy:** a regra de entrada pública (AWS-0107) hoje só olha SSH e RDP. Por isso existe o
  `conferir_plano.py`.
- **tflint:** para o RDS ele só confere o formato do nome. `db.inexistente.large` passa. A
  existência dos tipos usados aqui foi conferida na tabela de preços, não por essa regra.

Achados baixos do trivy, aceitos:

- **AWS-0017 (3 vezes):** os registros do CloudWatch usam a cifra padrão da AWS, não a nossa chave.
  Usar a nossa exigiria escrever o número da conta na política da chave.
- **AWS-0089 (1 vez):** o balde não registra cada acesso em outro balde.

### O simulador: Moto, não LocalStack

O LocalStack 2026.10.4 não sobe sem cadastro (saída 55, "License activation failed"). O
[Moto](https://github.com/getmoto/moto) 5.2.4 sobe sem nada. A ADR-0040 traz a prova e as
alternativas. O Moto tem lacunas, e cada uma virou um desvio documentado **só no simulador**:

| Lacuna do Moto 5.2.4 | O que acontece | Desvio |
|---|---|---|
| Não guarda a `RedriveAllowPolicy` | responde "Unknown Attribute", e o provedor espera sem fim (parei aos 5 min) | `restringir_fila_de_mortas = false` em `local/` |
| Não preenche `aws:SecureTransport` | a política "só TLS" recusa até pedido por HTTPS | o script remove a política antes de testar o aviso |
| Só desliga a trava de objeto com o balde vazio | o `destroy` falha com "Bucket needs to be empty" | o script esvazia o balde antes |

Por isso o `apply` cria 19 recursos e o `destroy` conta 18: a política do balde já saiu antes.

### A infraestrutura inteira no simulador (experimento)

`./scripts/nuvem.sh simular-tudo` tenta aplicar `aws/` inteira no Moto, com limite de 10 minutos. É um **experimento**: serve para descobrir até onde o simulador vai, não para aprovar nada, por isso fica fora do CI e não reprova nada quando dá erro. Na última rodada (30/10/2026, 604 s, parada pelo limite):

- **230 peças no plano; 193 ficaram no estado do Terraform.** Dessas, 185 concluíram a criação; as outras 8 entraram sem concluir (os 6 bancos RDS, por exemplo).
- **O que o Moto aceitou:** rede inteira (VPC, sub-redes, rotas, NAT), grupos de segurança, regras e perfis de IAM, KMS, ECR, Secrets Manager, S3, SQS, e o cluster EKS com o grupo de nós.

| O que não passou | O que aconteceu | De quem é a limitação |
|---|---|---|
| DocumentDB (`CreateDBCluster`) | 400: `Engine 'docdb' is not supported` | Moto: a lista dele não inclui `docdb` |
| MSK (`CreateConfiguration`) | 404 com página HTML | Moto: operação não implementada |
| Complementos do EKS (`CreateAddon`, 4) | 404 com página HTML | Moto: operação não implementada |
| Pod Identity (`CreatePodIdentityAssociation`, 10) | 404 com página HTML | Moto: operação não implementada |
| Regra das sondas do kubelet (9) | `The security group '' does not exist` | Moto: o EKS dele não devolve o grupo de segurança que a AWS cria para o cluster |
| ElastiCache (leitura depois de criar) | 404 em `DescribeCacheClusters` no nó `-001` | Moto: o grupo foi criado, mas a consulta do nó não o encontra |
| 6 instâncias RDS | Terraform desiste de esperar: `couldn't find resource` | Moto: criada, mas a consulta de espera do provedor não a encontra |
| `RedriveAllowPolicy` da fila de mortas | Moto aceita mas não guarda; o provedor espera até o limite | Moto (por isso `local/` desliga essa peça) |

Duas leituras importantes:

1. **Recusa aqui não prova erro no nosso código**, e **aceitação não prova que a AWS aceitaria**. O Moto não confere cota, combinação de versão com classe de instância, nem se o desenho funciona de verdade.
2. Só uma conta real confirma essas peças (DocumentDB, MSK, EKS funcionando, bancos). Este projeto escolheu não ter conta (ADR-0006).

## O que cada módulo cria

| Módulo | Cria | Por quê |
|---|---|---|
| `chaves` | uma chave KMS, com rotação anual | cifra bancos, cache, Kafka, segredos e imagens com uma chave nossa, que dá para auditar e revogar |
| `rede` | VPC, 3 zonas, sub-redes pública, de aplicação e de dados, NAT, registros de fluxo | a sub-rede de dados não tem rota para a internet, nem de saída |
| `eks` | cluster Kubernetes 1.37, nós `m7i.large` (3 a 6), registros, *add-ons* | onde os serviços rodam; os segredos do Kubernetes são cifrados com a nossa chave |
| `seguranca` | um grupo de segurança por serviço e um para o balanceador | só passa quem o desenho diz que chama (`pix` chama `razao`; o `extrato` não) |
| `bancos` | 6 RDS PostgreSQL 18 | TLS obrigatório, disco cifrado, backup, senha gerada e girada pela AWS |
| `cache` | Valkey em 2 nós, com *failover* | a trava rápida do Pix contra pedido repetido (ADR-0018) |
| `mensageria` | Kafka gerenciado (MSK) 4.1 com 3 *brokers* | autenticação por IAM, permissão por tópico, sem criação automática de tópico |
| `documentos` | DocumentDB com 2 instâncias | a leitura do extrato |
| `registro` | um repositório ECR por serviço (8; o Pix nativo não tem repositório próprio) | tag imutável, varredura ao enviar, limpeza automática |
| `armazenamento` | balde S3 dos relatórios, fila SQS e fila de mortas | trava de objeto: relatório não se apaga antes de 5 anos sem permissão especial |
| `segredos` | 6 senhas do Keycloak no Secrets Manager | geradas na hora, nunca escritas em arquivo |
| `identidades` | um papel IAM por serviço (*EKS Pod Identity*) | cada serviço lê só o próprio segredo, tópico e banco |

*Glossário rápido:* **VPC** é a rede privada na nuvem. **NAT** deixa a máquina privada sair para a
internet sem poder ser alcançada por ela. **KMS** é o cofre de chaves de cifra. **IAM** é o sistema
de permissões da AWS. **Grupo de segurança** é o firewall de cada peça. **Balde** (*bucket*) é a
pasta de arquivos do S3.

### Senhas fora do estado

O estado do Terraform é o arquivo em que ele anota o que criou, e costuma vazar senha. Aqui há dois
mecanismos:

- **Bancos:** `manage_master_user_password`. A própria AWS gera a senha e a guarda no Secrets
  Manager; o Terraform nunca a vê.
- **Demais senhas:** gerador efêmero (*ephemeral*, some ao fim do comando) e campo só de escrita
  (*write-only*, que não é gravado). Para trocar, sobe-se um número de versão.

Nenhuma saída do Terraform é senha: só o ARN (o endereço) do segredo.

## As decisões

**Um RDS por serviço, não um compartilhado.** Cada serviço já tem o próprio banco no projeto (a
regra do *database per service*). Compartilhar o servidor economizaria dinheiro, mas uma consulta
ruim do assistente poderia derrubar o razão, e o backup seria o mesmo para todos. Separados, o
razão tem máquina maior, réplica em outra zona (*Multi-AZ*) e 35 dias de backup; o antifraude tem
máquina pequena, sem réplica. O preço é real: **os 6 bancos custam cerca de US$ 1.030 por mês**, e
uma instância compartilhada `db.m7g.large` Multi-AZ com 240 GB custaria cerca de US$ 430. A
conciliação lê os bancos do Pix e do razão: ela tem acesso de rede e de IAM a esses dois, declarado
em `acessos_extras`. O pgvector do assistente é uma extensão disponível no RDS PostgreSQL, por isso
não há banco vetorial separado.

**DocumentDB, não MongoDB Atlas.** O Atlas é o MongoDB de verdade, 100% compatível com o que roda
no compose. O DocumentDB é da AWS e **fala a API do MongoDB, mas não é o MongoDB**. Escolhemos o
DocumentDB porque fica dentro da VPC, usa a mesma chave KMS e o mesmo provedor do Terraform, sem
outra conta, outro contrato e outra conexão privada para manter. O risco é aceitável por um motivo
específico: o extrato só faz *upsert*, `$set` e índices, e é uma projeção que se reconstrói a partir
do Kafka (`scripts/reconstruir-extrato.sh`). **Não testamos o extrato contra um DocumentDB.** Se
crescer para consultas mais ricas, a resposta muda para o Atlas.

**Valkey, não Redis.** O Valkey é o *fork* de código aberto do Redis, compatível com os comandos
usados aqui, e sai cerca de 20% mais barato no ElastiCache (US$ 0,0488 contra US$ 0,061 por hora).

**MSK com IAM.** Sem senha de Kafka para guardar: o papel de cada serviço diz quais tópicos ele lê
e escreve, no mesmo mapa que documenta o fluxo de eventos em `aws/main.tf`.

**Sem `data source` que consulta a AWS.** As zonas vêm de uma variável, e o número da conta é
extraído do ARN de um recurso. É o que permite o `plan` rodar sem conta.

**Estado remoto comentado.** O bloco do S3 está em `aws/versions.tf`, pronto, e explica o que fazer
antes de ligar.

## Quanto custaria

**É uma estimativa.** On-demand em `sa-east-1`, 730 horas por mês, sem imposto, nível gratuito ou
desconto. Preços lidos em 30/10/2026 na
[tabela oficial pública da AWS](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/index.json)
(arquivo `<Serviço>/current/sa-east-1/index.json`), que não pede login.

| Peça | Conta | US$/mês |
|---|---|---:|
| Kubernetes, plano de controle | US$ 0,10/h | 73 |
| Kubernetes, 3 nós `m7i.large` | 3 × US$ 0,16065/h | 352 |
| NAT, 3 unidades | 3 × US$ 0,093/h | 204 |
| IP público, 3 | 3 × US$ 0,005/h | 11 |
| RDS `razao`, `db.m7g.large` Multi-AZ | US$ 0,442/h | 323 |
| RDS `contas` e `pix`, `db.t4g.medium` Multi-AZ | 2 × US$ 0,275/h | 402 |
| RDS `keycloak`, `db.t4g.small` Multi-AZ | US$ 0,137/h | 100 |
| RDS `antifraude` e `assistente`, `db.t4g.small` | 2 × US$ 0,069/h | 101 |
| Disco dos RDS, gp3 | 220 GB × US$ 0,438 + 40 GB × US$ 0,219 | 105 |
| Valkey, 2 nós `cache.t4g.small` | 2 × US$ 0,0488/h | 71 |
| Kafka, 3 *brokers* `kafka.m7g.large` | 3 × US$ 0,3253/h | 712 |
| Disco do Kafka | 300 GB × US$ 0,19 | 57 |
| DocumentDB, 2 × `db.t4g.medium` | 2 × US$ 0,1552/h | 227 |
| KMS e 14 segredos | US$ 1 + 14 × US$ 0,40 | 7 |
| **Total das peças fixas** | | **cerca de 2.745** |

Fica de fora porque depende do uso:

| Peça | Preço unitário |
|---|---|
| Dados pelo NAT | US$ 0,093/GB |
| Balanceador (criado pelo Kubernetes, não por este código) | US$ 0,034/h + US$ 0,011 por LCU-hora |
| DocumentDB, disco e I/O | US$ 0,19/GB-mês + US$ 0,28 por milhão |
| Registros do CloudWatch | US$ 0,90/GB de ingestão + US$ 0,0408/GB-mês |
| S3 Standard e ECR | US$ 0,0405 e US$ 0,10/GB-mês |
| Transferência de dados | não levantado |

Onde cortar num ambiente de testes: um NAT só (`nat_por_zona = false`, menos US$ 143), sem réplica
nos bancos menores e o Kafka é o maior item isolado.

## O que falta (fora deste trabalho)

- **Aplicar numa conta real.** É o único teste que responde "a AWS aceita".
- **Os serviços Java não usam IAM ainda.** O código fala com Kafka e banco por usuário e senha. Na
  AWS, cada um precisaria da biblioteca de IAM do MSK e de token de autenticação do RDS, e cada
  banco precisaria do usuário do serviço criado com a permissão `rds_iam` (o Terraform não entra
  no banco).
- **Tópicos do Kafka:** a criação automática está desligada, e quem cria é um *Job* do Kubernetes.
- **Grupo de segurança por *pod*:** o Terraform cria os grupos, e a `SecurityGroupPolicy` que os
  liga aos *pods* é um objeto do Kubernetes.
- **Fora da nuvem:** o Ollama, que precisaria de máquina com GPU.
- **Sem WAF, DNS e certificado:** o balanceador é criado pelo Kubernetes, e esses itens ficam para
  quando houver um domínio.
