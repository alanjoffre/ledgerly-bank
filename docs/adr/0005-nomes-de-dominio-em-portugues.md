# ADR-0005: Nomes de domínio em português (linguagem ubíqua)

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Em código, é comum usar nomes em inglês. Mas o negócio deste projeto — um banco brasileiro, com
Pix, TED e conciliação — fala português, e muitos termos não têm tradução exata ("chave Pix",
"estorno", "lançamento"). Traduzir cria uma segunda língua entre o negócio e o código.

## Decisão

Seguir a **linguagem ubíqua** (*ubiquitous language*) do DDD: os nomes do domínio são os do
negócio, em português — `Conta`, `Lancamento`, `ChavePix`, `Dinheiro`, `ratear()`.

- Termos técnicos de framework ficam como o framework define: `ContaRepository`, `PixController`,
  `@Service`, `@Transactional`.
- Nomes sem acento nem cedilha nos identificadores Java (`Lancamento`, não `Lançamento`), para
  evitar problema de codificação em ferramentas.
- Documentação, mensagens de erro e testes (`@DisplayName`) em português.

## Alternativas consideradas

- **Tudo em inglês** — padrão em empresas internacionais, mas cria tradução constante entre o que
  o negócio fala e o que o código diz, e afasta o público brasileiro deste portfólio.

## Consequências

- **Positivas:** o código lê como a regra de negócio; o relatório de testes vira especificação em
  português; a conversa com área de negócio usa as mesmas palavras do código.
- **Negativas:** nomes mistos (`ContaRepository`) — é a convenção comum em empresas brasileiras e
  aceita aqui. Quem não lê português terá mais dificuldade para contribuir.
