-- Esquema do razão contábil.
--
-- O banco não confia cegamente na aplicação: as regras mais importantes também são
-- garantidas aqui (defesa em profundidade). Se um dia o código tiver um erro, o banco
-- recusa a gravação em vez de guardar dinheiro errado.
--
--   1. saldo_nao_negativo  — CHECK: conta de cliente nunca fica negativa;
--   2. partidas_dobradas   — gatilho: todo lançamento tem débitos = créditos;
--   3. razao_imutavel      — gatilho: lançamento gravado nunca é alterado nem apagado;
--   4. chave_idempotencia  — UNIQUE: o mesmo pedido nunca vira dois lançamentos.

-- Conta no razão. O "id" é o mesmo da conta no serviço de contas.
CREATE TABLE conta_razao (
    id                     UUID         PRIMARY KEY,
    -- DEVEDORA: o saldo aumenta com débito (ativo, por exemplo o caixa do banco).
    -- CREDORA:  o saldo aumenta com crédito (passivo, por exemplo o depósito do cliente).
    natureza               VARCHAR(8)   NOT NULL CHECK (natureza IN ('DEVEDORA', 'CREDORA')),
    moeda                  CHAR(3)      NOT NULL,
    -- NUMERIC(19,2): número decimal exato, nunca ponto flutuante (ADR-0007).
    saldo                  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    permite_saldo_negativo BOOLEAN      NOT NULL DEFAULT FALSE,
    situacao               VARCHAR(10)  NOT NULL CHECK (situacao IN ('ATIVA', 'BLOQUEADA', 'ENCERRADA')),
    aberta_em              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT saldo_nao_negativo CHECK (permite_saldo_negativo OR saldo >= 0)
);

-- Lançamento: um fato contábil (um depósito, um saque, uma transferência).
CREATE TABLE lancamento (
    id                 UUID         PRIMARY KEY,
    chave_idempotencia VARCHAR(100) NOT NULL,
    tipo               VARCHAR(20)  NOT NULL CHECK (tipo IN ('DEPOSITO', 'SAQUE', 'TRANSFERENCIA')),
    descricao          VARCHAR(140),
    registrado_em      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chave_idempotencia_unica UNIQUE (chave_idempotencia)
);

-- Partida: cada linha de débito ou crédito de um lançamento.
CREATE TABLE partida (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lancamento_id UUID           NOT NULL REFERENCES lancamento (id),
    conta_id      UUID           NOT NULL REFERENCES conta_razao (id),
    tipo          VARCHAR(7)     NOT NULL CHECK (tipo IN ('DEBITO', 'CREDITO')),
    valor         NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    -- Saldo da conta logo depois desta partida: o extrato sai pronto, sem recalcular.
    saldo_apos    NUMERIC(19, 2) NOT NULL
);

-- O extrato lê as partidas de uma conta da mais nova para a mais antiga.
CREATE INDEX partida_por_conta ON partida (conta_id, id DESC);
CREATE INDEX partida_por_lancamento ON partida (lancamento_id);

-- Regra 2: partidas dobradas. O gatilho é DEFERRABLE INITIALLY DEFERRED: só confere no
-- COMMIT, quando todas as partidas do lançamento já foram inseridas.
CREATE FUNCTION verificar_partidas_dobradas() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    diferenca  NUMERIC;
    quantidade INTEGER;
BEGIN
    SELECT COALESCE(SUM(CASE tipo WHEN 'DEBITO' THEN valor ELSE -valor END), 0), COUNT(*)
      INTO diferenca, quantidade
      FROM partida
     WHERE lancamento_id = NEW.lancamento_id;

    IF diferenca <> 0 OR quantidade < 2 THEN
        RAISE EXCEPTION 'Lançamento % desbalanceado: débitos - créditos = %, partidas = %',
            NEW.lancamento_id, diferenca, quantidade
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER partidas_dobradas
    AFTER INSERT ON partida
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verificar_partidas_dobradas();

-- Regra 3: o razão é só de acréscimo (append-only). Erro se corrige com estorno.
CREATE FUNCTION impedir_alteracao_do_razao() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'O razão é imutável: % em % não é permitido; corrija com um estorno',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER lancamento_imutavel
    BEFORE UPDATE OR DELETE ON lancamento
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao_do_razao();

CREATE TRIGGER partida_imutavel
    BEFORE UPDATE OR DELETE ON partida
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao_do_razao();

-- Conta interna "Caixa" do banco: o dinheiro físico que entra nos depósitos e sai nos saques.
-- Natureza devedora (é um ativo do banco). Id fixo, conhecido pelo código.
INSERT INTO conta_razao (id, natureza, moeda, saldo, permite_saldo_negativo, situacao)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEVEDORA', 'BRL', 0, FALSE, 'ATIVA');
