-- Esquema do serviço Pix.
--
-- Como no razão, as regras mais importantes também são garantidas aqui (defesa em profundidade):
--   1. chave_pix PRIMARY KEY      — uma chave aponta para uma conta só;
--   2. chave_idempotencia UNIQUE  — o mesmo pedido nunca vira duas transferências (com ou sem Redis);
--   3. situacao_coerente CHECK    — cada situação da Saga exige os lançamentos que a justificam.

-- O diretório de chaves (o "DICT" do Ledgerly).
CREATE TABLE chave_pix (
    chave         VARCHAR(77)  PRIMARY KEY,
    tipo          VARCHAR(10)  NOT NULL CHECK (tipo IN ('CPF', 'EMAIL', 'TELEFONE', 'ALEATORIA')),
    conta_id      UUID         NOT NULL,
    titular_nome  VARCHAR(120) NOT NULL,
    registrada_em TIMESTAMPTZ  NOT NULL
);

CREATE INDEX chave_pix_por_conta ON chave_pix (conta_id);

-- A transferência e o estado da Saga.
CREATE TABLE transferencia_pix (
    id                        UUID           PRIMARY KEY,
    chave_idempotencia        VARCHAR(100)   NOT NULL,
    pagador_conta_id          UUID           NOT NULL,
    pagador_nome              VARCHAR(120)   NOT NULL,
    recebedor_conta_id        UUID           NOT NULL,
    recebedor_nome            VARCHAR(120)   NOT NULL,
    recebedor_chave           VARCHAR(77)    NOT NULL,
    recebedor_tipo_chave      VARCHAR(10)    NOT NULL,
    valor                     NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    descricao                 VARCHAR(140),
    situacao                  VARCHAR(12)    NOT NULL CHECK (situacao IN
                                  ('CRIADA', 'DEBITADA', 'CONCLUIDA', 'REJEITADA', 'ESTORNANDO', 'ESTORNADA', 'EM_ANALISE')),
    motivo                    VARCHAR(400),
    lancamento_envio_id       UUID,
    lancamento_recebimento_id UUID,
    lancamento_estorno_id     UUID,
    -- Novas tentativas do passo que ficou sem resposta (espera exponencial).
    tentativas                INTEGER        NOT NULL DEFAULT 0,
    proxima_tentativa_em      TIMESTAMPTZ    NOT NULL,
    -- Reserva: até quando uma execução está com a transferência na mão (NULL = livre).
    reservada_ate             TIMESTAMPTZ,
    criada_em                 TIMESTAMPTZ    NOT NULL,
    finalizada_em             TIMESTAMPTZ,
    CONSTRAINT chave_idempotencia_unica UNIQUE (chave_idempotencia),
    CONSTRAINT pagador_diferente_do_recebedor CHECK (pagador_conta_id <> recebedor_conta_id),
    -- Regra 3: a situação tem de bater com os lançamentos. Uma transferência CONCLUIDA sem o
    -- lançamento de recebimento, por exemplo, é recusada pelo banco mesmo que o código erre.
    CONSTRAINT situacao_coerente CHECK (
        CASE situacao
            WHEN 'CRIADA'     THEN lancamento_envio_id IS NULL
            WHEN 'REJEITADA'  THEN lancamento_envio_id IS NULL
            WHEN 'DEBITADA'   THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
            WHEN 'CONCLUIDA'  THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NOT NULL
                                   AND lancamento_estorno_id IS NULL
            WHEN 'ESTORNANDO' THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
            WHEN 'ESTORNADA'  THEN lancamento_envio_id IS NOT NULL AND lancamento_estorno_id IS NOT NULL
                                   AND lancamento_recebimento_id IS NULL
            WHEN 'EM_ANALISE' THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
        END)
);

-- O agendador procura as Sagas paradas no meio. Índice parcial: só as não terminadas entram.
CREATE INDEX transferencia_pix_pendentes ON transferencia_pix (proxima_tentativa_em)
    WHERE situacao IN ('CRIADA', 'DEBITADA', 'ESTORNANDO');

-- Outbox (ADR-0013): PixConcluido e PixEstornado, gravados na mesma transação da situação nova.
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

CREATE INDEX outbox_pendentes ON outbox (id) WHERE publicado_em IS NULL;
