-- Outbox (ADR-0013): o evento é gravado aqui, na MESMA transação da mudança na conta, e um
-- publicador separado o entrega ao Kafka. Ou a conta muda e o evento fica gravado, ou nenhum dos dois.
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- ordem de gravação
    evento_id    UUID         NOT NULL UNIQUE,  -- o "eventoId" do envelope
    topico       VARCHAR(100) NOT NULL,
    chave        VARCHAR(100) NOT NULL,          -- a key do Kafka (id do agregado)
    tipo         VARCHAR(60)  NOT NULL,          -- vai também no cabeçalho "tipo"
    payload      JSONB        NOT NULL,          -- o envelope inteiro, pronto para mandar
    criado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    publicado_em TIMESTAMPTZ                     -- NULL = ainda não publicado
);

-- Índice parcial: só as linhas pendentes. O publicador consulta a cada 100 ms; com o índice, a
-- consulta não fica mais lenta à medida que a tabela acumula eventos já publicados.
CREATE INDEX outbox_pendentes ON outbox (id) WHERE publicado_em IS NULL;
