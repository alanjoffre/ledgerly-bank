/**
 * Tira as capturas de tela dos posts (docs/linkedin/imagens/) do sistema de verdade, no ar.
 * JavaScript puro (não TypeScript): roda com o "node" da máquina, sem compilar.
 *
 * Uso (em web/e2e): node capturas.mjs fase-5 <id de um Pix retido>
 *   fase-5  o Pix retido no backoffice (nota, motivos, explicação) e o assistente no internet banking
 */
import { chromium } from "@playwright/test";

const [qual, pix] = process.argv.slice(2);
const PASTA = new URL("../../docs/linkedin/imagens/", import.meta.url).pathname;

async function entrar(pagina, tela, usuario, senha) {
  await pagina.goto(tela);
  await pagina.getByRole("link", { name: "Entrar" }).click();
  await pagina.locator("#username").fill(usuario);
  await pagina.locator("#password").fill(senha);
  await pagina.locator("#kc-login").click();
  await pagina.getByRole("button", { name: "Sair" }).waitFor();
}

const navegador = await chromium.launch();
const nova = async () =>
  (await navegador.newContext({ viewport: { width: 1100, height: 900 }, deviceScaleFactor: 2, locale: "pt-BR" })).newPage();

if (qual === "fase-5") {
  const bo = await nova();
  await entrar(bo, "http://localhost:8090", "carla", "carla-local");
  await bo.goto(`http://localhost:8090/retidos/${pix}`);
  const porQue = bo.getByRole("region", { name: "Por que foi retido" });
  await porQue.getByText("Escrevendo a explicação…").waitFor({ state: "detached", timeout: 90_000 });
  await porQue.screenshot({ path: `${PASTA}fase-5-retido.png` });

  const ib = await nova();
  await entrar(ib, "http://localhost:8080", "ana", "ana-local");
  await ib.goto("http://localhost:8080/assistente");
  for (const pergunta of ["Qual é o meu saldo?", "Faça um Pix de R$ 50,00 para o Bruno."]) {
    await ib.getByLabel("Sua pergunta").fill(pergunta);
    await ib.getByRole("button", { name: "Perguntar" }).click();
    await ib.getByRole("button", { name: "Perguntar" }).waitFor({ state: "visible" });
    await ib.waitForFunction(
      (n) => document.querySelectorAll('[aria-label="Resposta do assistente"]').length >= n,
      pergunta.startsWith("Qual") ? 1 : 2,
      { timeout: 120_000 },
    );
  }
  await ib.getByRole("main").screenshot({ path: `${PASTA}fase-5-assistente.png` });
} else {
  console.error("uso: node capturas.mjs fase-5 <id do Pix retido>");
  process.exitCode = 2;
}
await navegador.close();
