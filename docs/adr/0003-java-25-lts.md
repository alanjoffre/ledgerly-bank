# ADR-0003: Java 25 (LTS)

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

O Java lança uma versão a cada seis meses; a cada dois anos, uma delas é **LTS** (*Long-Term
Support*, suporte longo), que é a que empresas — e bancos em especial — adotam em produção.
As LTS recentes são 17, 21 e 25 (setembro de 2025).

## Decisão

Java **25**, distribuição **Eclipse Temurin** (gratuita, do projeto Adoptium).

## Alternativas consideradas

- **Java 21** — ainda muito usado em produção e com *virtual threads*, mas o 25 traz recursos que
  este projeto quer mostrar na fase de concorrência (por exemplo *Scoped Values*, finalizados no 25)
  e melhorias de desempenho nas *virtual threads*.
- **Java 17** — sem *virtual threads*, que são centrais na Fase 3.

## Consequências

- **Positivas:** recursos atuais da linguagem; suporte longo; Spring Boot 4 e Quarkus 3 suportam.
- **Negativas:** algumas bibliotecas antigas podem demorar a suportar; recursos ainda em *preview*
  (como *Structured Concurrency*) exigem a flag `--enable-preview` e serão isolados e sinalizados.
