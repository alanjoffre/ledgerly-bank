import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, type Page } from "@playwright/test";

export const INTERNET_BANKING = process.env.IB_URL ?? "http://localhost:8080";
export const BACKOFFICE = process.env.BO_URL ?? "http://localhost:8090";

/** As pessoas do realm local (infra/docker/keycloak/ledgerly-realm.json) — senhas só de ambiente local. */
export const PESSOAS = {
  ana: { usuario: "ana", senha: "ana-local", nome: "Ana Exemplo", chave: "ana@exemplo.test" },
  bruno: { usuario: "bruno", senha: "bruno-local", nome: "Bruno Exemplo", chave: "bruno@exemplo.test" },
  carla: { usuario: "carla", senha: "carla-local" },
} as const;

/** Abre a tela, clica em Entrar e faz o login na página do Keycloak (o navegador nunca vê o token). */
export async function entrar(page: Page, tela: string, pessoa: { usuario: string; senha: string }) {
  await page.goto(tela);
  await page.getByRole("link", { name: "Entrar" }).click();
  await expect(page).toHaveURL(/\/realms\/ledgerly\/protocol\/openid-connect\/auth/);
  await page.locator("#username").fill(pessoa.usuario);
  await page.locator("#password").fill(pessoa.senha);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(new RegExp(`^${tela}/`));
  await expect(page.getByRole("button", { name: "Sair" })).toBeVisible();
}

/** "R$ 1.234,56" (com espaço comum ou NBSP) → centavos. */
export function centavos(texto: string): number {
  const limpo = texto.replace(/[^\d,-]/g, "").replace(",", "");
  return Number(limpo);
}

const RAIZ = fileURLToPath(new URL("../../..", import.meta.url));

/** Roda um script do repositório (scripts/...) e devolve a última linha da saída. */
export function script(nome: string, ...args: string[]): string {
  const saida = execFileSync(`${RAIZ}scripts/${nome}`, args, { cwd: RAIZ, encoding: "utf8" });
  return saida.trim().split("\n").at(-1)!;
}
