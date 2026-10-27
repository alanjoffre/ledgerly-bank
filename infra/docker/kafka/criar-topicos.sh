#!/bin/bash
# Cria os tópicos do Kafka do Ledgerly Bank, no contêiner "kafka-topicos" do compose. Pode rodar
# de novo sem erro (--if-not-exists).
#
# O Kafka local tem a criação automática de tópicos desligada: um nome digitado errado vira erro,
# não um tópico novo. Por isso todo tópico nasce aqui, de propósito, com as regras dele.
#
# Nome: <serviço dono>.<assunto>.v<versão do contrato>. Uma mudança que quebra quem consome
# ganha um tópico novo (v2), e os dois convivem enquanto os consumidores migram.
# O contrato de cada evento está em docs/arquitetura/eventos.md.
set -euo pipefail

SERVIDOR="${KAFKA_SERVIDOR:-kafka:9092}"
PARTICOES=3

criar() {
    local topico="$1"; shift
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$SERVIDOR" --create --if-not-exists \
        --topic "$topico" --partitions "$PARTICOES" --replication-factor 1 "$@"
}

# Eventos de conta (aberta, bloqueada, desbloqueada, encerrada). Quem consome: razao.
criar contas.eventos.v1
# Lançamentos do razão. Retenção infinita (retention.ms=-1): é o histórico de onde o extrato pode
# ser reconstruído do zero a qualquer momento.
criar razao.lancamentos.v1 --config retention.ms=-1
# Transferências Pix concluídas e estornadas. Quem consome: extrato.
criar pix.transferencias.v1

# Dead letter topics (DLT): a mensagem que o consumidor não consegue processar, mesmo depois das
# novas tentativas, vai para cá — em vez de travar a fila inteira — e fica guardada para análise.
criar contas.eventos.v1.dlt
criar razao.lancamentos.v1.dlt
criar pix.transferencias.v1.dlt

/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$SERVIDOR" --list

# Marca de pronto + espera parado: o healthcheck do compose confere a marca, e assim
# "docker compose up --wait" só termina depois que os tópicos existem.
touch /tmp/topicos-criados
exec sleep infinity
