-- Fase 2: movimentos do Pix e a tabela de saída de eventos (Outbox).

-- 1. Os três movimentos do Pix (ver docs/arquitetura/eventos.md e a ADR-0015):
--    PIX_ENVIO       cliente pagador  -> Liquidação Pix
--    PIX_RECEBIMENTO Liquidação Pix   -> cliente recebedor
--    PIX_ESTORNO     Liquidação Pix   -> cliente pagador (o crédito foi recusado; o dinheiro volta)
ALTER TABLE lancamento DROP CONSTRAINT lancamento_tipo_check;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_tipo_check CHECK (tipo IN (
    'DEPOSITO', 'SAQUE', 'TRANSFERENCIA', 'PIX_ENVIO', 'PIX_RECEBIMENTO', 'PIX_ESTORNO'));

-- 2. Conta interna "Liquidação Pix": onde fica o dinheiro de um Pix em andamento, entre sair do
--    pagador e chegar ao recebedor. Natureza credora (o banco deve esse dinheiro a alguém).
--    Não pode ficar negativa: o razão recusa creditar um Pix que nunca foi debitado.
INSERT INTO conta_razao (id, natureza, moeda, saldo, permite_saldo_negativo, situacao)
VALUES ('00000000-0000-0000-0000-000000000002', 'CREDORA', 'BRL', 0, FALSE, 'ATIVA');

-- 3. Outbox (ADR-0013): o evento é gravado aqui, na MESMA transação do lançamento. Um publicador
--    separado lê os pendentes e manda ao Kafka. Ou os dois ficam gravados, ou nenhum.
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evento_id    UUID         NOT NULL UNIQUE,
    topico       VARCHAR(100) NOT NULL,
    chave        VARCHAR(100) NOT NULL,
    tipo         VARCHAR(60)  NOT NULL,
    payload      JSONB        NOT NULL,
    criado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    publicado_em TIMESTAMPTZ
);

-- Índice parcial: só as linhas pendentes entram nele, então ele fica pequeno mesmo com milhões de
-- eventos já publicados — e a consulta do publicador continua rápida.
CREATE INDEX outbox_pendentes ON outbox (id) WHERE publicado_em IS NULL;
