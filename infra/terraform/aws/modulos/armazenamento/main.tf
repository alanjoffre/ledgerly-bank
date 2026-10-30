# Módulo armazenamento: o balde (*bucket*) S3 dos relatórios e a fila que avisa quando um chega.
#
# A conciliação em lote (ADR-0022) gera um relatório por dia. Na nuvem ele vai para o S3, e o S3
# avisa uma fila SQS (*Simple Queue Service*) a cada arquivo novo; quem quiser reagir (mandar
# e-mail à tesouraria, abrir um alerta) lê a fila, sem a conciliação precisar saber quem é.
#
# Este é o módulo que o simulador local (infra/terraform/local) aplica DE VERDADE: o mesmo código.
#
# Regras do balde, cada uma com o porquê:
# - versionamento: sobrescrever ou apagar um relatório guarda a versão anterior;
# - trava de objeto (*Object Lock*, modo GOVERNANCE): nem quem tem acesso apaga um relatório antes
#   do prazo sem uma permissão especial e registrada — a "gaveta lacrada" que a auditoria pede;
# - cifrado com a nossa chave KMS, com *bucket key* (menos chamadas ao KMS, conta menor);
# - acesso público bloqueado nas quatro chaves, e recusa de qualquer acesso sem TLS;
# - ciclo de vida: o relatório velho desce para uma classe de armazenamento mais barata.

resource "random_id" "sufixo" {
  # Nome de balde é único no MUNDO inteiro; o sufixo evita colidir com o de outra pessoa.
  byte_length = 4
}

locals {
  balde = "${var.nome}-relatorios-${random_id.sufixo.hex}"
}

resource "aws_s3_bucket" "relatorios" {
  bucket              = local.balde
  object_lock_enabled = var.trava_de_objeto
  force_destroy       = var.apagar_com_conteudo

  tags = { Name = local.balde }
}

resource "aws_s3_bucket_ownership_controls" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "relatorios" {
  bucket                  = aws_s3_bucket.relatorios.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.chave_kms_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "relatorios" {
  count  = var.trava_de_objeto ? 1 : 0
  bucket = aws_s3_bucket.relatorios.id

  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = var.dias_de_guarda
    }
  }

  depends_on = [aws_s3_bucket_versioning.relatorios]
}

resource "aws_s3_bucket_lifecycle_configuration" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id

  rule {
    id     = "relatorio-velho-mais-barato"
    status = "Enabled"
    filter {}

    transition {
      days          = 90
      storage_class = "GLACIER_IR"
    }

    noncurrent_version_expiration {
      noncurrent_days = var.dias_de_guarda
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.relatorios]
}

resource "aws_s3_bucket_policy" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "SoComTLS"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.relatorios.arn, "${aws_s3_bucket.relatorios.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.relatorios]
}

# A fila --------------------------------------------------------------------------------------
# Cifra da fila pela chave do próprio SQS (SSE-SQS), e não pela nossa KMS: para o S3 entregar
# numa fila cifrada com chave nossa, a política da chave teria de liberar o serviço S3, e a
# política padrão (sem o número da conta, que o plan sem conta não tem) não libera.

resource "aws_sqs_queue" "mortas" {
  # Fila de mensagens mortas (*dead-letter queue*): o aviso que falhou 5 vezes vem para cá, em vez
  # de se perder ou de travar a fila principal. Alguém olha, conserta e devolve.
  name                      = "${var.nome}-relatorios-prontos-mortas"
  message_retention_seconds = 1209600 # 14 dias, o máximo
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "relatorios_prontos" {
  name                       = "${var.nome}-relatorios-prontos"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600 # 4 dias
  receive_wait_time_seconds  = 20     # espera longa: menos chamadas vazias, conta menor
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.mortas.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "mortas" {
  # Só a fila dos relatórios pode usar esta fila de mortas (nenhuma outra fila da conta).
  # Desligável SÓ por causa do simulador: o Moto 5.2.4 aceita o pedido mas não guarda a regra, e
  # o provedor fica esperando ela aparecer para sempre (ADR-0040).
  count = var.restringir_fila_de_mortas ? 1 : 0

  queue_url = aws_sqs_queue.mortas.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.relatorios_prontos.arn]
  })
}

resource "aws_sqs_queue_policy" "relatorios_prontos" {
  queue_url = aws_sqs_queue.relatorios_prontos.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "SoOBaldeDosRelatorios"
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.relatorios_prontos.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_s3_bucket.relatorios.arn } }
    }]
  })
}

resource "aws_s3_bucket_notification" "relatorios" {
  bucket = aws_s3_bucket.relatorios.id

  queue {
    queue_arn     = aws_sqs_queue.relatorios_prontos.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "conciliacao/"
  }

  depends_on = [aws_sqs_queue_policy.relatorios_prontos]
}
