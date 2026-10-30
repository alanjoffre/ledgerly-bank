-- O banco do antifraude (Fase 5, ADR-0032), no mesmo padrão "um banco por serviço" do 01.
--
-- Ao contrário do 01, este pode rodar de novo sem erro: o Postgres roda a pasta só na primeira
-- subida, e quem já tinha o volume das fases anteriores cria o banco com
--   ./scripts/servicos.sh subir antifraude
-- (o script roda este arquivo dentro do contêiner quando o banco ainda não existe).

SELECT 'CREATE USER antifraude WITH PASSWORD ''antifraude_local'''
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'antifraude')\gexec

SELECT 'CREATE DATABASE antifraude OWNER antifraude'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'antifraude')\gexec

REVOKE CONNECT ON DATABASE antifraude FROM PUBLIC;
