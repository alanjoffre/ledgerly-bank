-- O banco do assistente (Fase 5, ADR-0036), no padrão "um banco por serviço".
--
-- A extensão vector é criada aqui, pelo administrador: criar extensão exige superusuário, e o
-- usuário do assistente não é um. Ele só cria as tabelas dele (os trechos e o índice).

CREATE USER assistente WITH PASSWORD 'assistente_local';
CREATE DATABASE assistente OWNER assistente;
REVOKE CONNECT ON DATABASE assistente FROM PUBLIC;

\connect assistente
CREATE EXTENSION vector;
