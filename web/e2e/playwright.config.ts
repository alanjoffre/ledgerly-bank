import { defineConfig, devices } from "@playwright/test";

/**
 * E2E: um navegador de verdade (Chromium) contra o sistema no ar — Keycloak, os dois gateways (BFF)
 * e os quatro serviços. Antes: infraestrutura, ./scripts/servicos.sh subir e ./scripts/semear.sh.
 *
 * Um teste por vez (workers: 1): os testes mexem no saldo das mesmas contas (a Ana envia Pix e
 * recebe a devolução); em paralelo, o saldo conferido por um seria mexido pelo outro.
 */
export default defineConfig({
  testDir: "testes",
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    ...devices["Desktop Chrome"],
    locale: "pt-BR",
    timezoneId: "America/Sao_Paulo",
    trace: "retain-on-failure",
    // VIDEO=1 grava cada teste em test-results/, para a demonstração (docs/guias/demo-fase-7.md).
    video: process.env.VIDEO === "1" ? { mode: "on", size: { width: 1280, height: 720 } } : "off",
  },
});
