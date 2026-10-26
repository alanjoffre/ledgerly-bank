# Como contribuir

Obrigado pelo interesse! Este é um projeto de estudo, mas segue as práticas de um time profissional.

## Fluxo

1. Crie um branch a partir da `main` (`git switch -c feat/nome-curto`).
2. Faça a mudança **com testes**.
3. Rode `./mvnw verify` — precisa passar (testes, cobertura mínima, regras de arquitetura).
4. Abra um *pull request*; o CI roda sozinho.

## Mensagens de commit

O projeto segue o padrão **Conventional Commits**: a mensagem começa com o **tipo** da mudança.
Isso permite gerar o histórico de versões automaticamente e entender o commit sem abri-lo.

| Tipo | Quando usar | Exemplo |
| --- | --- | --- |
| `feat` | funcionalidade nova | `feat(razao): registrar lançamento em partidas dobradas` |
| `fix` | correção de erro | `fix(dinheiro): rateio de valor negativo` |
| `docs` | só documentação | `docs(adr): ADR-0008 sobre Outbox` |
| `test` | só testes | `test(pix): cenário de estorno` |
| `refactor` | muda a estrutura sem mudar o comportamento | `refactor(contas): extrair validação de CPF` |
| `build` / `ci` | build, dependências, CI | `ci: cache do Maven` |
| `chore` | manutenção que não se encaixa acima | `chore: atualizar .gitignore` |

O texto entre parênteses (opcional) é a parte afetada.

## Convenções de código

- **Nomes de domínio em português**, sem acento nos identificadores (`Lancamento`); termos de
  framework como vêm (`ContaRepository`). Motivo na [ADR-0005](docs/adr/0005-nomes-de-dominio-em-portugues.md).
- **Dinheiro é sempre `Dinheiro`**, nunca `double`, `float` ou `BigDecimal` solto.
- **Testes descrevem a regra** em português com `@DisplayName`.
- **Decisão cara de desfazer vira ADR** ([modelo](docs/adr/0000-modelo.md)).
