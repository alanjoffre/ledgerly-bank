-- Padrão "um banco por serviço" (database per service).
--
-- Cada serviço tem o próprio banco e o próprio usuário, e não enxerga o banco dos
-- outros. Se o serviço de Pix precisar de um dado das contas, ele pede pela API ou
-- recebe por evento — nunca lê a tabela direto. Isso deixa cada serviço livre para
-- mudar o próprio esquema sem quebrar os vizinhos.
--
-- Localmente os três bancos dividem uma instância do Postgres (economia de memória);
-- na nuvem, cada um pode ter a sua.
--
-- Este script roda só na primeira subida do contêiner (volume vazio).

CREATE USER contas WITH PASSWORD 'contas_local';
CREATE DATABASE contas OWNER contas;

CREATE USER razao WITH PASSWORD 'razao_local';
CREATE DATABASE razao OWNER razao;

CREATE USER pix WITH PASSWORD 'pix_local';
CREATE DATABASE pix OWNER pix;

-- Ninguém além do dono conecta em cada banco.
REVOKE CONNECT ON DATABASE contas FROM PUBLIC;
REVOKE CONNECT ON DATABASE razao FROM PUBLIC;
REVOKE CONNECT ON DATABASE pix FROM PUBLIC;
