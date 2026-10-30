# ADR-0040: Validar o Terraform sem conta e simular com o Moto, no lugar do LocalStack

- **Status:** Aceita
- **Data:** 2026-10-30

Complementa a [ADR-0006](0006-nuvem-com-custo-zero.md): continua valendo o custo
zero e o "escrever sem aplicar". Muda o simulador, que lá era o LocalStack, e define o que conta
como validação.

## Contexto

A ADR-0006 escolheu o LocalStack para simular S3, SQS e Secrets Manager. Ao construir a Fase 6
testamos de novo, em 30/10/2026, e ele não sobe mais sem conta:

```
docker run localstack/localstack:2026.10.4
Localstack returning with exit code 55.
License activation failed!
Reason: No credentials were found in the environment. Please set the LOCALSTACK_AUTH_TOKEN
variable to a valid auth token.
```

O [anúncio da versão 2026.03](https://blog.localstack.cloud/localstack-for-aws-release-2026-03-0/)
explica: a imagem passou a exigir um *token* de autenticação. Há um plano gratuito para uso não
comercial, mas exige cadastro e um segredo no CI. Quem clona o projeto não rodaria nada sem criar
conta, e isso contraria o motivo da própria ADR-0006.

A outra pergunta é o que dá para provar sem conta. Um `plan` com credencial falsa calcula tudo,
mas não pergunta nada à AWS. E a varredura de segurança pode não enxergar o que se espera dela:
medimos que o trivy 0.75 não acusa um banco na porta 5432 aberto para `0.0.0.0/0`, porque a regra
AWS-0107 hoje só olha SSH e RDP.

## Decisão

- **Simulador:** usamos o [Moto](https://github.com/getmoto/moto) (`motoserver/moto:5.2.4`,
  licença Apache 2.0, sem cadastro nem *token*), fixado pelo resumo (*digest*) da imagem.
- **Validação:** `scripts/nuvem.sh validar` roda `fmt -check`, `validate`, `plan` com credencial
  falsa, regras do projeto sobre o plano, tflint e trivy. As opções de "não conferir a conta"
  ficam num arquivo *override* usado só numa cópia temporária: o código de produção fica limpo.
- **Regras do projeto:** `infra/terraform/validacao/conferir_plano.py` lê o plano e recusa
  qualquer entrada da internet fora da porta 443 do balanceador, cobrindo o ponto cego do trivy.
- **Simulação:** `scripts/nuvem.sh simular` aplica e destrói de verdade `infra/terraform/local`,
  que chama os mesmos módulos da AWS, e confere o resultado com o aws-cli.
- **Na documentação, cada afirmação diz de que tipo é:** validado, simulado ou não verificado.

## Alternativas consideradas

- **LocalStack com o plano gratuito** — o simulador mais completo, mas exige conta e segredo no
  CI de quem quiser reproduzir.
- **Fixar uma imagem antiga do LocalStack, anterior à exigência** — congela o projeto numa versão
  que não recebe mais correção nem acompanha o provedor da AWS.
- **Só o `plan`, sem simulador** — não executa nada; erro de ordem entre recursos ou de resposta
  da API só apareceria no primeiro `apply` real.
- **Regra própria em Rego dentro do trivy** — funcionaria, mas lê o código antes de expandir
  módulos e laços; o plano já vem expandido, com o endereço exato de cada recurso.

## Consequências

- **Positivas:** qualquer pessoa roda tudo sem conta, sem cadastro e sem custo; o mesmo script
  roda na máquina e no CI; o simulador pegou comportamento real do código (ver abaixo).
- **Negativas:** o Moto imita a API, não as regras da AWS. Ele aceitar não prova que a AWS
  aceitaria: permissões do KMS e do IAM, cotas e combinações de versão com tipo de máquina não são
  conferidas. E tem lacunas próprias, que nos obrigaram a três desvios só no simulador:
  - não guarda a `RedriveAllowPolicy` da fila e o provedor espera por ela sem fim, então ela fica
    desligada em `infra/terraform/local` por uma variável (ligada na AWS);
  - não preenche a condição `aws:SecureTransport`, e recusa até pedido por HTTPS no balde com a
    política "só TLS", então o script remove a política antes de testar o fluxo de aviso;
  - só deixa desligar a trava de objeto com o balde vazio, então o script esvazia o balde antes
    do `destroy`.

  O detalhe de cada ponto, com a medição, está em `infra/terraform/README.md`.
