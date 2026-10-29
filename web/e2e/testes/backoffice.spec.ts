import { expect, test } from "@playwright/test";
import { BACKOFFICE, entrar, PESSOAS, script } from "./apoio";

test.describe("backoffice (analista)", () => {
  test("a Carla vê o Pix em análise na fila, devolve ao titular e ele sai da fila", async ({ page }) => {
    const pix = script("pix-em-analise.sh", "ana", "bruno", "45.67");

    await entrar(page, BACKOFFICE, PESSOAS.carla);
    await expect(page.getByRole("heading", { name: "Fila de análise" })).toBeVisible();
    const naFila = page.locator(`a[href="/transferencias/${pix}"]`);
    await expect(naFila).toBeVisible();
    await naFila.click();

    const oPix = page.getByRole("region", { name: "O Pix" });
    await expect(oPix).toContainText("45,67");
    await expect(oPix).toContainText("a conta do pagador foi encerrada");

    const devolver = page.getByRole("button", { name: "Devolver" });
    await page.getByLabel(/Justificativa/).fill("curto");
    await expect(devolver).toBeDisabled();
    await page.getByLabel(/Justificativa/).fill("Conta de origem encerrada; devolvido à conta ativa da titular.");
    await expect(page.getByLabel("Conta que recebe a devolução")).toHaveValue(/.+/);
    await devolver.click();
    await expect(page.getByRole("status")).toContainText("Devolução registrada");

    await page.getByRole("link", { name: "Voltar à fila" }).last().click();
    await expect(page.getByRole("heading", { name: "Fila de análise" })).toBeVisible();
    await expect(page.locator(`a[href="/transferencias/${pix}"]`)).toHaveCount(0);
  });

  test("uma cliente não entra no backoffice", async ({ page }) => {
    await entrar(page, BACKOFFICE, PESSOAS.ana);
    await expect(page.getByText("Acesso negado: o backoffice é só da equipe de análise.")).toBeVisible();
    expect((await page.request.get(`${BACKOFFICE}/api/v1/transferencias?situacao=EM_ANALISE`)).status()).toBe(403);
  });
});
