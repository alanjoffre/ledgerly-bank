# Post do LinkedIn — Fase 4 (Segurança e front-end)

> Imagem: [imagens/fase-4.png](imagens/fase-4.png) (fonte: [imagens/fase-4.html](imagens/fase-4.html);
> a captura é o internet banking de verdade, tirada pelo Playwright).
> Achados do E2E na [ADR-0025](../adr/0025-bff-um-por-front-end.md) e na
> [ADR-0029](../adr/0029-react-vite-vitest-playwright.md); BOLA na
> [ADR-0026](../adr/0026-bola-com-404.md); custo do BFF medido na ADR-0025.

---

Todos os testes do meu banco passavam. Aí um navegador de verdade fez login, enviou um Pix e abriu o extrato: 5 defeitos. 🔍

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Esta fase foi segurança e as telas. Keycloak para o login (OAuth2 com PKCE), um BFF para cada tela, e duas telas em React: o internet banking do cliente e o backoffice de fraude da analista.

O BFF (Backend for Frontend) é o porteiro da tela. O login acontece no Keycloak, e o token fica guardado no BFF: o navegador só recebe um cookie de sessão. Um script malicioso na página não tem token nenhum para roubar.

Os testes de ponta a ponta com Playwright abrem o Chromium e fazem o que a pessoa faz. E acharam o que nenhum teste de unidade via:

1️⃣ Um "acesso negado" (403) virava "deu certo" (200), com a tela no corpo: o erro caía no fallback da SPA.
2️⃣ O Pix pela tela falhava: ia a chave mascarada que a consulta devolve (b***@...), e não a digitada.
3️⃣ Depois do login, a pessoa caía numa página de JSON: o BFF guardava o último pedido sem sessão e voltava para ele.
4️⃣ A mensagem do Pix ("Aluguel") nunca chegava ao extrato. Esse estava ali desde a Fase 2.
5️⃣ "Saldo indisponível" em toda conta: o teste da tela usava um falso que trazia o saldo na lista, e a API real não traz.

Os dois últimos têm a mesma lição: o teste com falso prova a tela contra o falso. Só o teste contra o sistema de verdade prova a tela contra a API. Cada defeito ganhou primeiro um teste que falhava, e depois a correção.

O que mais entrou nesta fase:
🔹 BOLA, a falha nº 1 do OWASP para APIs: pedir a conta de outra pessoa responde 404, como se ela não existisse. Nem a existência vaza.
🔹 Todo serviço exige token, inclusive de outro serviço (client credentials). O razão contábil nem tem rota na internet: só outro serviço fala com ele.
🔹 A analista devolve um Pix travado em análise para outra conta do mesmo titular, com auditoria de cada decisão.
🔹 O BFF custa cerca de 1 ms por pedido, medido com Gatling: direto no serviço e pelo BFF, na mesma máquina.

844 testes automatizados (782 no back-end, 57 nas telas e 5 de ponta a ponta), tudo documentado em português.

Próxima fase: IA aplicada, com antifraude e um assistente para o extrato.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #React #SegurancaDaInformacao #OAuth2 #BuildInPublic
