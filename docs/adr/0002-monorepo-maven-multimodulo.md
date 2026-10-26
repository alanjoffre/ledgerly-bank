# ADR-0002: Monorepo com Maven multimódulo

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

O projeto terá cinco serviços, um front-end e bibliotecas compartilhadas. É preciso decidir se cada
um vive no seu próprio repositório (*polyrepo*) ou todos num só (*monorepo*), e com que ferramenta
de build.

## Decisão

Um repositório só, com **Maven multimódulo**: um `pom.xml` pai na raiz define versão do Java,
versões de bibliotecas e regras de qualidade; cada serviço e biblioteca é um módulo filho. O
**Maven Wrapper** (`mvnw`) vai junto, para que ninguém precise instalar o Maven.

## Alternativas consideradas

- **Um repositório por serviço** — é o modelo de empresas grandes, com um time por serviço. Aqui há
  uma pessoa só: cinco repositórios significam cinco CIs, cinco READMEs e mudanças que atravessam
  serviços em vários PRs. E quem avalia o portfólio teria de abrir cinco links.
- **Gradle** — build mais rápido e mais flexível, mas o Maven é o padrão no mercado financeiro
  brasileiro e o que a maioria das vagas pede. O Maven também é mais previsível (declarativo).

## Consequências

- **Positivas:** uma mudança que atravessa serviços é um commit só; versões iguais em todo lugar
  (o `maven-enforcer-plugin` barra conflito de versão); um clone e um comando rodam tudo.
- **Negativas:** o CI compila tudo a cada mudança (aceitável no tamanho deste projeto). Serviços
  Spring e Quarkus **não** herdam o BOM um do outro — cada serviço importa o BOM do seu framework,
  para não misturar versões.
