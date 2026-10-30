# ADR-0027: Token de serviço entre serviços, e o razão sem rota pública

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Os serviços também conversam entre si: o pix chama o razão (débito, crédito, estorno) e o contas
(titular da conta); o contas chama o razão (encerramento); o extrato chama o contas (dono da conta,
ADR-0026). Se esses caminhos ficarem sem token, basta alcançar a rede interna para lançar no
razão. E o razão é o coração do banco: todo dinheiro que se move passa por um lançamento dele.

## Decisão

- **Todo serviço exige token**, inclusive de outro serviço. Um serviço chamando outro usa o fluxo
  ***client credentials*** (o serviço se identifica com o próprio id e segredo no Keycloak, sem
  pessoa no meio) e recebe um token com o papel `servico`.
  - **Spring** (contas, extrato): `OAuth2ClientHttpRequestInterceptor` no `RestClient`, com um
    gerente de tokens de serviço (`AuthorizedClientServiceOAuth2AuthorizedClientManager`) e um
    "dono" fixo: o token é do serviço, nunca da pessoa que fez o pedido. Ele é guardado e
    renovado perto de vencer; num 401, é descartado e pedido de novo.
  - **Quarkus** (pix): `quarkus-oidc-client` + `@OidcClientFilter` nos REST clients.
- **O razão aceita só `servico`**, em todo `/v1`. Nenhuma pessoa — nem a analista — fala com ele
  direto: o gateway **não tem rota** para o razão (o teste `razaoNaoTemRota` confere).
- **Scripts, semeadura e carga** usam o cliente local `ledgerly-ferramentas` (também papel
  `servico`): `scripts/lib/token.sh` nos scripts, `TokenDeServico` no Gatling.

## Alternativas consideradas

- **Repassar o token da pessoa** de serviço em serviço — o razão passaria a ver "a Ana" lançando
  direto, e precisaria das regras de cliente; um token de pessoa vazado lançaria no razão.
- **Rede confiável (sem token entre serviços)** — o modelo antigo de "perímetro": quem entra na rede
  pode tudo. O mercado foi para *zero trust* exatamente por isso.
- **mTLS (certificado em cada serviço)** — prova a identidade do serviço também, mas exige uma
  autoridade certificadora e rotação de certificados; num ambiente local de portfólio, o token já
  ensina o principal. É o passo natural com uma *service mesh*. **A Fase 6 trouxe o Kubernetes, mas não a *mesh*:** segue só o token.

## Consequências

- **Positivas:** o razão só é alcançável por quem tem credencial de serviço; os serviços conferem
  papel em toda chamada; o token de serviço é guardado e renovado, sem uma ida ao Keycloak por pedido.
- **Negativas:** o Keycloak passa a ser dependência de toda chamada entre serviços que precisar
  de token novo (a cada ~5 minutos); todo script antigo precisou do token (demos das Fases 1 a 3,
  medições e Gatling foram ajustados); mais segredos de cliente para guardar (locais aqui; num
  cofre em nuvem).
