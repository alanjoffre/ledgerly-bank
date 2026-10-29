-- Fase 4: a devolução de um Pix em análise (ADR-0030).
--
-- O analista do backoffice decide devolver o dinheiro de um Pix que ficou EM_ANALISE para outra
-- conta do mesmo titular. A conta da volta fica na própria transferência; quem decidiu e por quê
-- fica na trilha de auditoria — uma linha por decisão, que nunca é alterada nem apagada.

ALTER TABLE transferencia_pix ADD COLUMN conta_devolucao_id UUID;

-- Só existe conta de devolução depois da decisão: a Saga volta a estornar, e estorna para ela.
ALTER TABLE transferencia_pix ADD CONSTRAINT devolucao_so_depois_da_analise CHECK (
    conta_devolucao_id IS NULL OR situacao IN ('ESTORNANDO', 'ESTORNADA', 'EM_ANALISE'));

-- A fila do backoffice: os Pix em análise, do mais antigo para o mais novo.
CREATE INDEX transferencia_pix_em_analise ON transferencia_pix (criada_em) WHERE situacao = 'EM_ANALISE';

CREATE TABLE decisao_de_analise (
    id               UUID         PRIMARY KEY,
    transferencia_id UUID         NOT NULL REFERENCES transferencia_pix (id),
    -- O "sub" do token do analista (o identificador dele no Keycloak) e o nome, para ler sem consultar.
    analista_id      VARCHAR(64)  NOT NULL,
    analista_nome    VARCHAR(120) NOT NULL,
    decisao          VARCHAR(10)  NOT NULL CHECK (decisao IN ('DEVOLVER')),
    conta_destino_id UUID         NOT NULL,
    justificativa    VARCHAR(500) NOT NULL CHECK (char_length(justificativa) >= 10),
    decidida_em      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX decisao_de_analise_por_transferencia ON decisao_de_analise (transferencia_id);
