/**
 * Chamada ao BFF (o gateway deste front-end).
 *
 * - O navegador manda só o cookie de sessão (HttpOnly: este código não o lê). O token do Keycloak
 *   fica no gateway e nunca chega aqui (ADR-0025).
 * - CSRF: em todo POST/DELETE vai o cabeçalho X-XSRF-TOKEN com o valor do cookie XSRF-TOKEN, que o
 *   gateway manda. Um site de fora não consegue ler esse cookie, então não consegue forjar o pedido.
 * - Erro vem como Problem Details (RFC 9457): vira um ErroDaApi com o título e o detalhe.
 */

export type Problema = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
};

export class ErroDaApi extends Error {
  readonly status: number;
  readonly tipo: string | undefined;

  constructor(status: number, problema: Problema) {
    super(problema.detail ?? problema.title ?? `Erro ${status}`);
    this.name = "ErroDaApi";
    this.status = status;
    this.tipo = problema.type;
  }

  /** Sem sessão (ou a sessão venceu): a tela manda para o login. */
  get semSessao(): boolean {
    return this.status === 401;
  }
}

export type Opcoes = {
  metodo?: "GET" | "POST" | "DELETE";
  corpo?: unknown;
  cabecalhos?: Record<string, string>;
  sinal?: AbortSignal;
};

export function lerCookie(nome: string, cookies: string = document.cookie): string | undefined {
  for (const parte of cookies.split(";")) {
    const [chave, ...valor] = parte.trim().split("=");
    if (chave === nome) {
      return decodeURIComponent(valor.join("="));
    }
  }
  return undefined;
}

function cabecalhosDe(opcoes: Opcoes): Headers {
  const cabecalhos = new Headers({ Accept: "application/json", ...opcoes.cabecalhos });
  if (opcoes.corpo !== undefined) {
    cabecalhos.set("Content-Type", "application/json");
  }
  if ((opcoes.metodo ?? "GET") !== "GET") {
    const xsrf = lerCookie("XSRF-TOKEN");
    if (xsrf) {
      cabecalhos.set("X-XSRF-TOKEN", xsrf);
    }
  }
  return cabecalhos;
}

async function problemaDe(resposta: Response): Promise<Problema> {
  try {
    return (await resposta.json()) as Problema;
  } catch {
    return { title: resposta.statusText };
  }
}

/** Resposta inteira (status e cabeçalhos): para quem precisa olhar o 202 ou o Idempotent-Replayed. */
export async function chamarComResposta<T>(caminho: string, opcoes: Opcoes = {}): Promise<{ corpo: T; resposta: Response }> {
  const resposta = await fetch(caminho, {
    method: opcoes.metodo ?? "GET",
    headers: cabecalhosDe(opcoes),
    body: opcoes.corpo === undefined ? undefined : JSON.stringify(opcoes.corpo),
    credentials: "same-origin",
    signal: opcoes.sinal,
  });
  if (!resposta.ok) {
    throw new ErroDaApi(resposta.status, await problemaDe(resposta));
  }
  const texto = await resposta.text();
  return { corpo: (texto ? JSON.parse(texto) : undefined) as T, resposta };
}

export async function chamar<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  return (await chamarComResposta<T>(caminho, opcoes)).corpo;
}

export type Eu = { nome: string; papeis: string[]; clienteId: string | null };

/** Quem está logado; null se não houver sessão. */
export async function quemSouEu(): Promise<Eu | null> {
  try {
    return await chamar<Eu>("/api/eu");
  } catch (erro) {
    if (erro instanceof ErroDaApi && erro.semSessao) {
      return null;
    }
    throw erro;
  }
}

/** O login é uma navegação de verdade (não fetch): o navegador vai ao Keycloak e volta. */
export const ENDERECO_DE_LOGIN = "/oauth2/authorization/keycloak";

/** Sair: o gateway apaga a sessão e devolve o endereço de saída do Keycloak, para onde a página vai. */
export async function sair(navegar: (url: string) => void = (url) => window.location.assign(url)): Promise<void> {
  const { redirecionar } = await chamar<{ redirecionar: string }>("/logout", { metodo: "POST" });
  navegar(redirecionar);
}
