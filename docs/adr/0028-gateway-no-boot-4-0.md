# ADR-0028: O gateway no Spring Boot 4.0, enquanto o Spring Cloud do 4.1 não sai

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Os serviços Spring do projeto estão no **Spring Boot 4.1.2**. O gateway (ADR-0025) usa o **Spring
Cloud Gateway**, que vem no "trem" do Spring Cloud: cada versão do Spring Cloud é feita para uma
linha do Boot. Em 28/10/2026:

- **Spring Cloud 2025.1.x** (Gateway 5.0.x) → Boot **4.0**; a última é a 2025.1.4;
- **Spring Cloud 2026.0** (para o Boot 4.1) → ainda em *release candidate* (RC1).

Pôr uma RC num sistema de banco, ou forçar o 2025.1 em cima do Boot 4.1 (uma combinação que o
próprio Spring Cloud não suporta), é trocar um problema de agora por um problema escondido.

## Decisão

O módulo `servicos/gateway` usa o **Spring Boot 4.0.9** (o último 4.0.x) e o **Spring Cloud
2025.1.4** (Gateway Server WebMVC 5.0.4). É **o único** módulo fora do Boot 4.1: o `pom.xml` dele
importa os BOMs da própria linha, em vez de herdar a versão do resto.

- O gateway não depende das bibliotecas internas que exigem o 4.1 (ele não é *resource server*;
  lê os papéis do token com a própria Nimbus que o Spring Security traz).
- Quando o Spring Cloud 2026.0 sair em versão final (GA), o gateway sobe para o Boot 4.1. É uma
  troca de duas versões no `pom.xml` e uma rodada dos 37 testes.
- A variante **WebMVC** (servlet, com *virtual threads*) e não a WebFlux: o projeto inteiro é
  servlet e a equipe não precisa de um segundo modelo de programação só para o gateway.

## Alternativas consideradas

- **Spring Cloud 2026.0.0-RC1 no Boot 4.1** — tudo na mesma versão, mas uma RC no caminho de
  todo pedido de cliente.
- **Spring Cloud 2025.1 em cima do Boot 4.1** — combinação fora da matriz de compatibilidade; pode
  funcionar até o dia em que não funciona.
- **Gateway sem o Spring Cloud** (um proxy escrito à mão com `RestClient`) — sem rotas declarativas,
  sem o filtro de TokenRelay pronto; mais código de segurança próprio.
- **Outro produto** (Envoy, Kong, NGINX) — são ótimos *proxies*, mas o BFF precisa de sessão,
  login OIDC e renovação de token, que neles vira plugin ou script.

## Consequências

- **Positivas:** só versões finais em produção; a decisão é explícita e tem gatilho de saída (o GA
  do 2026.0).
- **Negativas:** duas linhas do Boot no mesmo repositório (o Dependabot precisa respeitar a do
  gateway); uma correção de segurança só do 4.1 não chega ao gateway até a troca.
