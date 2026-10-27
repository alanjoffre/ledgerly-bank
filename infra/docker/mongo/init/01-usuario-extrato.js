// Usuário próprio do serviço extrato, como no PostgreSQL: um banco por serviço, e cada serviço só
// enxerga o próprio banco. O extrato lê e escreve no banco "extrato" e não tem acesso a mais nada
// (nem ao banco "admin" do MongoDB).
//
// Este script roda só na PRIMEIRA subida do contêiner (volume vazio), como os do PostgreSQL. Senha
// de ambiente LOCAL, de propósito simples e pública; na nuvem ela vem de um cofre (ADR-0006).
//
// Num volume que já existia, crie o usuário à mão (uma vez):
//   docker compose -f infra/docker/compose.yaml exec -T mongo \
//     mongosh -u ledgerly_admin -p ledgerly_local --quiet < infra/docker/mongo/init/01-usuario-extrato.js

const extrato = db.getSiblingDB('extrato');
if (extrato.getUser('extrato') === null) {
  extrato.createUser({
    user: 'extrato',
    pwd: 'extrato_local',
    roles: [{ role: 'readWrite', db: 'extrato' }],
  });
  print('Usuário extrato criado.');
} else {
  print('Usuário extrato já existe.');
}
