-- Fase 3: as contas quentes (Caixa e Liquidação Pix) divididas em sub-contas. Ver a ADR-0019.
--
-- O problema medido em 28/10/2026: todo depósito trava a linha do Caixa e todo Pix trava a da
-- Liquidação Pix, então pedidos de clientes diferentes fazem fila na mesma linha — 124 depósitos
-- por segundo, contra 954 transferências entre clientes no mesmo razão.

-- 1. As sub-contas 1 a 15 de cada conta quente (a sub-conta 0 é a conta que já existia).
--    O id: 64 bits altos zerados, o penúltimo byte é a sub-conta e o último, a conta-mãe
--    (01 = Caixa, 02 = Liquidação Pix). Exemplo: 00000000-0000-0000-0000-000000000301 é a
--    sub-conta 3 do Caixa.
INSERT INTO conta_razao (id, natureza, moeda, saldo, permite_saldo_negativo, situacao)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(k * 256 + 1), 12, '0'))::uuid, 'DEVEDORA', 'BRL', 0, TRUE, 'ATIVA'
  FROM generate_series(1, 15) AS k;

INSERT INTO conta_razao (id, natureza, moeda, saldo, permite_saldo_negativo, situacao)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(k * 256 + 2), 12, '0'))::uuid, 'CREDORA', 'BRL', 0, FALSE, 'ATIVA'
  FROM generate_series(1, 15) AS k;

-- 2. Uma sub-conta do Caixa pode ficar negativa; o Caixa inteiro, não.
--    Um cliente deposita na sub-conta 3 e outro saca da 7: a 7 fica negativa e a soma continua
--    certa. A soma nunca fica negativa por causa da própria contabilidade: o Caixa é a única conta
--    devedora, então Caixa = clientes + Liquidação Pix — e essas nunca ficam negativas. A
--    conferência de integridade (GET /v1/integridade) confere a soma.
UPDATE conta_razao SET permite_saldo_negativo = TRUE WHERE id = '00000000-0000-0000-0000-000000000001';

-- 3. A Liquidação Pix continua sem saldo negativo, em cada sub-conta: o recebimento e o estorno
--    saem da mesma sub-conta em que o envio entrou, apontado por envio_pix_id.
--    E cada envio tem no máximo UMA saída. Antes, o razão aceitava receber e também estornar o
--    mesmo envio, desde que a Liquidação tivesse dinheiro de outros Pix: o pagador recebia de volta
--    e o recebedor recebia também — dinheiro criado do nada. Só a Saga do Pix impedia.
ALTER TABLE lancamento ADD COLUMN envio_pix_id UUID REFERENCES lancamento (id);

CREATE UNIQUE INDEX uma_saida_por_envio ON lancamento (envio_pix_id) WHERE envio_pix_id IS NOT NULL;

-- NOT VALID: vale para os lançamentos novos; os de antes da Fase 3 não têm a referência.
ALTER TABLE lancamento ADD CONSTRAINT envio_pix_so_na_saida CHECK (
    (envio_pix_id IS NOT NULL) = (tipo IN ('PIX_RECEBIMENTO', 'PIX_ESTORNO'))) NOT VALID;
