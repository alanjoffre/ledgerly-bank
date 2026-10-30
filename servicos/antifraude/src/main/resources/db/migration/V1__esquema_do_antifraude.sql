-- Esquema do antifraude (ADR-0032).
--
-- Uma linha por Pix avaliado, com tudo o que entrou na nota: os fatos, a nota, a decisão, os
-- motivos e o modelo. É o registro que responde, meses depois, "por que este Pix foi retido?" — e
-- é também o histórico de onde sai o costume de cada pagador (média, rajada, recebedor conhecido).

CREATE TABLE avaliacao (
    -- A transferência do pix: avaliar de novo o mesmo Pix devolve esta linha, não cria outra.
    transferencia_id            UUID           PRIMARY KEY,
    pagador_conta_id            UUID           NOT NULL,
    recebedor_conta_id          UUID           NOT NULL,
    valor                       NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    instante                    TIMESTAMPTZ    NOT NULL,
    conta_recebedora_aberta_em  TIMESTAMPTZ    NOT NULL,
    -- Os fatos que o modelo leu (sem histórico, razao_da_media fica nula).
    razao_da_media              NUMERIC(19, 4),
    pix_na_ultima_hora          INTEGER        NOT NULL CHECK (pix_na_ultima_hora >= 0),
    recebedor_novo              BOOLEAN        NOT NULL,
    madrugada                   BOOLEAN        NOT NULL,
    idade_conta_recebedora_dias BIGINT         NOT NULL CHECK (idade_conta_recebedora_dias >= 0),
    nota                        INTEGER        NOT NULL CHECK (nota BETWEEN 0 AND 1000),
    decisao                     VARCHAR(10)    NOT NULL CHECK (decisao IN ('APROVAR', 'SEGURAR', 'RECUSAR')),
    -- [{"codigo": "regra:rajada", "texto": "6 Pix do pagador na última hora"}, ...]
    motivos                     JSONB          NOT NULL,
    modelo                      VARCHAR(60)    NOT NULL,
    avaliada_em                 TIMESTAMPTZ    NOT NULL
);

-- O histórico do pagador: os últimos Pix dele, do mais novo para o mais velho.
CREATE INDEX avaliacao_por_pagador ON avaliacao (pagador_conta_id, instante DESC);
