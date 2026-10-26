# ADR-0001: Registrar as decisões de arquitetura com ADR

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

O Ledgerly Bank é um projeto de portfólio: quem lê o repositório (recrutador técnico, pessoa
entrevistadora, outro dev) quer entender **como penso**, não só o que funciona. Um código pronto
mostra o resultado; não mostra as alternativas que descartei nem o preço que aceitei pagar.

## Decisão

Toda decisão de arquitetura que custe caro para desfazer vira uma ADR em `docs/adr/`, no formato
de Michael Nygard (contexto, decisão, alternativas, consequências).

## Alternativas consideradas

- **Explicar tudo no README** — o README fica enorme e mistura "como rodar" com "por que é assim".
- **Wiki do GitHub** — fica fora do repositório: não versiona junto com o código nem passa por revisão.
- **Não documentar** — a decisão se perde, e o projeto parece uma lista de tecnologias.

## Consequências

- **Positivas:** cada escolha tem justificativa rastreável; a ADR vira roteiro de entrevista.
- **Negativas:** custa tempo escrever. Para não virar burocracia, só decisões caras de desfazer
  ganham ADR — trocar o nome de uma variável não é decisão de arquitetura.
