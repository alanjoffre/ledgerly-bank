-- Fase 5: o antifraude antes do débito (ADR-0033).
--
-- Duas situações novas, ambas antes de qualquer dinheiro sair:
--   APROVADA — o antifraude deixou (ou, fora do ar, a regra de valor deixou): o próximo passo é o débito;
--   RETIDA   — parada esperando a analista do backoffice liberar ou recusar.
-- A nota do antifraude fica na transferência; os motivos completos ficam no banco do antifraude.

ALTER TABLE transferencia_pix DROP CONSTRAINT transferencia_pix_situacao_check;
ALTER TABLE transferencia_pix ADD CONSTRAINT transferencia_pix_situacao_check CHECK (situacao IN
    ('CRIADA', 'APROVADA', 'RETIDA', 'DEBITADA', 'CONCLUIDA', 'REJEITADA', 'ESTORNANDO', 'ESTORNADA', 'EM_ANALISE'));

ALTER TABLE transferencia_pix DROP CONSTRAINT situacao_coerente;
ALTER TABLE transferencia_pix ADD CONSTRAINT situacao_coerente CHECK (
    CASE situacao
        WHEN 'CRIADA'     THEN lancamento_envio_id IS NULL
        WHEN 'APROVADA'   THEN lancamento_envio_id IS NULL
        WHEN 'RETIDA'     THEN lancamento_envio_id IS NULL
        WHEN 'REJEITADA'  THEN lancamento_envio_id IS NULL
        WHEN 'DEBITADA'   THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
        WHEN 'CONCLUIDA'  THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NOT NULL
                               AND lancamento_estorno_id IS NULL
        WHEN 'ESTORNANDO' THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
        WHEN 'ESTORNADA'  THEN lancamento_envio_id IS NOT NULL AND lancamento_estorno_id IS NOT NULL
                               AND lancamento_recebimento_id IS NULL
        WHEN 'EM_ANALISE' THEN lancamento_envio_id IS NOT NULL AND lancamento_recebimento_id IS NULL
    END);

-- De 0 a 1000; NULL antes da avaliação, nos Pix de antes da Fase 5 e quando o antifraude não respondeu.
ALTER TABLE transferencia_pix ADD COLUMN nota_de_risco INTEGER CHECK (nota_de_risco BETWEEN 0 AND 1000);

-- O agendador também retoma as aprovadas (o serviço caiu entre a avaliação e o débito).
DROP INDEX transferencia_pix_pendentes;
CREATE INDEX transferencia_pix_pendentes ON transferencia_pix (proxima_tentativa_em)
    WHERE situacao IN ('CRIADA', 'APROVADA', 'DEBITADA', 'ESTORNANDO');

-- A fila dos retidos, do mais antigo para o mais novo.
CREATE INDEX transferencia_pix_retidas ON transferencia_pix (criada_em) WHERE situacao = 'RETIDA';

-- A auditoria passa a ter as decisões sobre o Pix retido; só a devolução tem conta de destino.
ALTER TABLE decisao_de_analise DROP CONSTRAINT decisao_de_analise_decisao_check;
ALTER TABLE decisao_de_analise ADD CONSTRAINT decisao_de_analise_decisao_check
    CHECK (decisao IN ('DEVOLVER', 'LIBERAR', 'RECUSAR'));
ALTER TABLE decisao_de_analise ALTER COLUMN conta_destino_id DROP NOT NULL;
ALTER TABLE decisao_de_analise ADD CONSTRAINT conta_destino_so_na_devolucao
    CHECK ((decisao = 'DEVOLVER') = (conta_destino_id IS NOT NULL));
