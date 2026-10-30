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

  test("a Carla vê por que o antifraude reteve o Pix e libera: o Pix segue até CONCLUIDA", async ({ page }) => {
    const pix = script("pix-retido.sh", "ana", "bruno", "1500.00");

    await entrar(page, BACKOFFICE, PESSOAS.carla);
    await page.getByRole("link", { name: "Pix retidos" }).click();
    const naFila = page.locator(`a[href="/retidos/${pix}"]`);
    await expect(naFila).toBeVisible();
    await naFila.click();

    await expect(page.getByRole("region", { name: "O Pix" })).toContainText("1.500,00");
    const porQue = page.getByRole("region", { name: "Por que foi retido" });
    // A nota não é fixa: a regra da conta "laranja" põe 500, e de madrugada o modelo soma o sinal do
    // horário (882 às 4h). O teste cravava "500" e falhava conforme a hora em que rodava.
    await expect(porQue).toContainText(/Nota \d{3} de 1000/);
    await expect(porQue).toContainText("para uma conta aberta há menos de 1 dia, que nunca recebeu deste pagador");

    await page.getByLabel(/Justificativa/).fill("Cliente confirmou o Pix por telefone; conta nova do próprio irmão.");
    await page.getByRole("button", { name: "Liberar" }).click();
    await expect(page.getByRole("status")).toContainText("Pix liberado. Agora está CONCLUIDA.");

    await page.getByRole("link", { name: "Voltar à fila" }).click();
    await expect(page.getByRole("heading", { name: "Pix retidos" })).toBeVisible();
    await expect(page.locator(`a[href="/retidos/${pix}"]`)).toHaveCount(0);
  });

  test("a Carla recusa um Pix retido: REJEITADA, sem dinheiro movido", async ({ page }) => {
    // R$ 1.500 e não R$ 2.000: de madrugada, R$ 2.000 passa da nota 900 e o antifraude recusa
    // sozinho, sem chegar à fila (ADR-0033).
    const pix = script("pix-retido.sh", "ana", "bruno", "1500.00");

    await entrar(page, BACKOFFICE, PESSOAS.carla);
    await page.goto(`${BACKOFFICE}/retidos/${pix}`);
    await page.getByLabel(/Justificativa/).fill("Cliente não reconhece o Pix: golpe do falso parente.");
    await page.getByRole("button", { name: "Recusar" }).click();
    await expect(page.getByRole("status")).toContainText("Pix recusado. Nenhum dinheiro saiu.");

    const consulta = await page.request.get(`${BACKOFFICE}/api/v1/transferencias/${pix}`);
    const corpo = await consulta.json();
    expect(corpo.situacao).toBe("REJEITADA");
    expect(corpo.lancamentos?.envio ?? null).toBeNull();
  });

  test("uma cliente não entra no backoffice", async ({ page }) => {
    await entrar(page, BACKOFFICE, PESSOAS.ana);
    await expect(page.getByText("Acesso negado: o backoffice é só da equipe de análise.")).toBeVisible();
    expect((await page.request.get(`${BACKOFFICE}/api/v1/transferencias?situacao=EM_ANALISE`)).status()).toBe(403);
  });
});
