-- Esquema do serviço de contas: clientes e contas. O saldo NÃO está aqui — é do razão.

CREATE TABLE cliente (
    id            UUID         PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    -- Só os 11 dígitos. Dado pessoal (LGPD): a API devolve mascarado (***.ddd.ddd-**).
    cpf           VARCHAR(11)  NOT NULL CHECK (cpf ~ '^[0-9]{11}$'),
    cadastrado_em TIMESTAMPTZ  NOT NULL,
    CONSTRAINT cpf_unico UNIQUE (cpf)
);

-- Números de conta vêm de uma sequência: nunca se repetem, nem com cadastros simultâneos.
CREATE SEQUENCE numero_conta_seq START WITH 100001;

CREATE TABLE conta (
    id           UUID        PRIMARY KEY,
    cliente_id   UUID        NOT NULL REFERENCES cliente (id),
    agencia      VARCHAR(4)  NOT NULL,
    numero       INTEGER     NOT NULL,
    digito       INTEGER     NOT NULL CHECK (digito BETWEEN 0 AND 9),
    situacao     VARCHAR(10) NOT NULL CHECK (situacao IN ('ATIVA', 'BLOQUEADA', 'ENCERRADA')),
    aberta_em    TIMESTAMPTZ NOT NULL,
    encerrada_em TIMESTAMPTZ,
    -- Trava otimista: o Hibernate soma 1 a cada UPDATE e só grava se a versão ainda for a lida.
    versao       BIGINT      NOT NULL,
    CONSTRAINT numero_conta_unico UNIQUE (agencia, numero),
    CONSTRAINT encerrada_tem_data CHECK ((situacao = 'ENCERRADA') = (encerrada_em IS NOT NULL))
);

CREATE INDEX conta_por_cliente ON conta (cliente_id);
