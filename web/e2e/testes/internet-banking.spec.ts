import { expect, test } from "@playwright/test";
import { centavos, entrar, INTERNET_BANKING, PESSOAS } from "./apoio";

test.describe("internet banking (cliente)", () => {
  test("a Ana entra, envia um Pix para a chave do Bruno e vê no extrato", async ({ page }) => {
    await entrar(page, INTERNET_BANKING, PESSOAS.ana);
    await expect(page.getByRole("heading", { name: "Minhas contas" })).toBeVisible();
    const conta = page.locator("section.cartao", { has: page.getByText("Ativa") }).first();
    const antes = centavos(await conta.getByTestId("saldo").innerText());

    await conta.getByRole("link", { name: "Enviar Pix" }).click();
    await page.getByLabel("Chave Pix de quem recebe").fill(PESSOAS.bruno.chave);
    await page.getByLabel("Valor (R$)").fill("12,34");
    await page.getByLabel("Mensagem (opcional)").fill("Pix do teste E2E");
    await page.getByRole("button", { name: "Continuar" }).click();

    // Conferência: o nome de quem recebe vem do DICT, antes de confirmar.
    const conferir = page.getByRole("region", { name: "Conferir o Pix" });
    await expect(conferir).toContainText(PESSOAS.bruno.nome);
    await page.getByRole("button", { name: "Confirmar Pix" }).click();
    await expect(page.getByRole("status")).toHaveText("Pix enviado.", { timeout: 20_000 });

    // Extrato: é um modelo de leitura (CQRS), atualizado pelos eventos — pode levar um instante.
    await page.getByRole("link", { name: "Ver extrato" }).click();
    await expect(async () => {
      await page.reload();
      await expect(page.getByRole("row").filter({ hasText: "Pix do teste E2E" }).first()).toContainText("12,34", { timeout: 1_000 });
    }).toPass({ timeout: 20_000 });

    await page.getByRole("link", { name: /Minhas contas/ }).first().click();
    await expect(async () => {
      await page.reload();
      expect(centavos(await conta.getByTestId("saldo").innerText({ timeout: 1_000 }))).toBe(antes - 1234);
    }).toPass({ timeout: 20_000 });
  });

  test("BOLA: a Ana não enxerga a conta do Bruno nem pela API (404, como se não existisse)", async ({ browser }) => {
    // O id da conta do Bruno, pela sessão do próprio Bruno.
    const doBruno = await browser.newContext();
    const paginaDoBruno = await doBruno.newPage();
    await entrar(paginaDoBruno, INTERNET_BANKING, PESSOAS.bruno);
    const eu = await (await paginaDoBruno.request.get(`${INTERNET_BANKING}/api/eu`)).json();
    const contas = await (await paginaDoBruno.request.get(`${INTERNET_BANKING}/api/v1/clientes/${eu.clienteId}/contas`)).json();
    const contaDoBruno: string = contas[0].id;
    await doBruno.close();

    const daAna = await browser.newContext();
    const pagina = await daAna.newPage();
    await entrar(pagina, INTERNET_BANKING, PESSOAS.ana);
    for (const caminho of [
      `/api/v1/contas/${contaDoBruno}`,
      `/api/v1/contas/${contaDoBruno}/saldo`,
      `/api/v1/contas/${contaDoBruno}/extrato`,
      `/api/v1/contas/${contaDoBruno}/chaves`,
      `/api/v1/clientes/${eu.clienteId}/contas`,
    ]) {
      const resposta = await pagina.request.get(INTERNET_BANKING + caminho);
      expect(resposta.status(), caminho).toBe(404);
    }
    // E pela tela: o extrato da conta alheia não aparece.
    await pagina.goto(`${INTERNET_BANKING}/contas/${contaDoBruno}`);
    await expect(pagina.getByText(/não encontrad|Ainda não há movimentação/i).first()).toBeVisible();
    await expect(pagina.getByText("Pix do teste E2E")).toHaveCount(0);
    await daAna.close();
  });

  test("sem sessão a API responde 401, e Sair encerra a sessão também no Keycloak", async ({ page }) => {
    const semSessao = await page.request.get(`${INTERNET_BANKING}/api/eu`);
    expect(semSessao.status()).toBe(401);

    await entrar(page, INTERNET_BANKING, PESSOAS.ana);
    await page.getByRole("button", { name: "Sair" }).click();
    await expect(page.getByRole("link", { name: "Entrar" })).toBeVisible();
    expect((await page.request.get(`${INTERNET_BANKING}/api/eu`)).status()).toBe(401);

    // Entrar de novo pede a senha: a sessão do Keycloak (SSO) também acabou.
    await page.getByRole("link", { name: "Entrar" }).click();
    await expect(page.locator("#password")).toBeVisible();
  });
});
