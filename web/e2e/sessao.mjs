/**
 * Faz o login de uma pessoa num navegador de verdade e imprime "conta=<id> cookie=<nome>=<valor>":
 * a sessão do BFF que a medição de carga do gateway usa (desempenho/carga/medir-gateway.sh).
 * JavaScript puro (não TypeScript): roda com o "node" da máquina, sem compilar.
 *
 * Uso (em web/e2e): node sessao.mjs [usuário] [senha] [endereço do gateway]
 */
import { chromium } from "@playwright/test";

const [usuario = "ana", senha = "ana-local", tela = "http://localhost:8080"] = process.argv.slice(2);
const navegador = await chromium.launch();
const contexto = await navegador.newContext();
const pagina = await contexto.newPage();
await pagina.goto(tela);
await pagina.getByRole("link", { name: "Entrar" }).click();
await pagina.locator("#username").fill(usuario);
await pagina.locator("#password").fill(senha);
await pagina.locator("#kc-login").click();
await pagina.getByRole("button", { name: "Sair" }).waitFor();
const eu = await (await pagina.request.get(`${tela}/api/eu`)).json();
const contas = await (await pagina.request.get(`${tela}/api/v1/clientes/${eu.clienteId}/contas`)).json();
const sessao = (await contexto.cookies(tela)).find((c) => c.name.startsWith("LEDGERLY_"));
console.log(`conta=${contas.find((c) => c.situacao === "ATIVA").id} cookie=${sessao.name}=${sessao.value}`);
await navegador.close();
