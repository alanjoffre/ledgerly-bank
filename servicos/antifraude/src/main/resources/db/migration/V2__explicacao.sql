-- A explicação de cada avaliação para a analista (ADR-0035).
--
-- Fica na mesma linha da avaliação: é dela, nasce uma vez e não muda. Nasce na primeira vez que
-- alguém do backoffice pede (não no POST do Pix: o modelo de linguagem leva segundos, e o Pix não
-- espera por texto). Vazia enquanto ninguém pediu, ou enquanto o modelo de linguagem não respondeu.

ALTER TABLE avaliacao
    ADD COLUMN explicacao          TEXT,
    ADD COLUMN explicacao_origem   TEXT CHECK (explicacao_origem IN ('MODELO_DE_LINGUAGEM', 'TEXTO_FIXO')),
    ADD COLUMN explicacao_modelo   TEXT,
    ADD COLUMN explicada_em        TIMESTAMPTZ,
    ADD CONSTRAINT explicacao_completa CHECK (
        (explicacao IS NULL) = (explicacao_origem IS NULL)
        AND (explicacao IS NULL) = (explicada_em IS NULL)
        AND (explicacao_origem = 'MODELO_DE_LINGUAGEM') = (explicacao_modelo IS NOT NULL));
