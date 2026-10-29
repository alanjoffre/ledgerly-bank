import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { chamar, chamarComResposta, ErroDaApi, lerCookie, quemSouEu, sair } from "./api.ts";

function resposta(status: number, corpo?: unknown, cabecalhos: Record<string, string> = {}): Response {
  return new Response(corpo === undefined ? null : JSON.stringify(corpo), {
    status,
    headers: { "Content-Type": "application/json", ...cabecalhos },
  });
}

describe("chamada ao BFF", () => {
  const fetchFalso = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchFalso);
    document.cookie = "XSRF-TOKEN=abc%3D123";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchFalso.mockReset();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("GET leva o cookie da sessão e não leva CSRF", async () => {
    fetchFalso.mockResolvedValue(resposta(200, { ok: true }));
    expect(await chamar("/api/v1/contas/1")).toEqual({ ok: true });
    const [caminho, pedido] = fetchFalso.mock.calls[0]!;
    expect(caminho).toBe("/api/v1/contas/1");
    expect(pedido?.credentials).toBe("same-origin");
    expect(new Headers(pedido?.headers).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("POST leva o corpo em JSON e o cabeçalho de CSRF com o valor do cookie", async () => {
    fetchFalso.mockResolvedValue(resposta(201, { id: "x" }));
    await chamar("/api/v1/transferencias", { metodo: "POST", corpo: { valor: "10.00" }, cabecalhos: { "Idempotency-Key": "k" } });
    const pedido = fetchFalso.mock.calls[0]![1]!;
    const cabecalhos = new Headers(pedido.headers);
    expect(cabecalhos.get("X-XSRF-TOKEN")).toBe("abc=123");
    expect(cabecalhos.get("Content-Type")).toBe("application/json");
    expect(cabecalhos.get("Idempotency-Key")).toBe("k");
    expect(pedido.body).toBe('{"valor":"10.00"}');
  });

  it("erro vira ErroDaApi com o detalhe do Problem Details", async () => {
    fetchFalso.mockResolvedValue(resposta(422, { type: "urn:ledgerly:erro:saldo-insuficiente", title: "Saldo insuficiente", detail: "Falta R$ 5,00" }));
    const erro = await chamar("/api/x").catch((e: unknown) => e);
    expect(erro).toBeInstanceOf(ErroDaApi);
    expect((erro as ErroDaApi).message).toBe("Falta R$ 5,00");
    expect((erro as ErroDaApi).tipo).toBe("urn:ledgerly:erro:saldo-insuficiente");
    expect((erro as ErroDaApi).semSessao).toBe(false);
  });

  it("erro sem corpo JSON usa o texto do status", async () => {
    fetchFalso.mockResolvedValue(new Response("<html>", { status: 502, statusText: "Bad Gateway" }));
    await expect(chamar("/api/x")).rejects.toThrow("Bad Gateway");
  });

  it("204 sem corpo devolve undefined; a resposta inteira fica disponível", async () => {
    fetchFalso.mockResolvedValue(new Response(null, { status: 204 }));
    const { corpo, resposta: r } = await chamarComResposta("/api/x", { metodo: "DELETE" });
    expect(corpo).toBeUndefined();
    expect(r.status).toBe(204);
  });

  it("quemSouEu: sem sessão (401) é null; outro erro sobe", async () => {
    fetchFalso.mockResolvedValueOnce(resposta(401, { title: "Não autenticado" }));
    expect(await quemSouEu()).toBeNull();
    fetchFalso.mockResolvedValueOnce(resposta(200, { nome: "ana", papeis: ["cliente"], clienteId: "c1" }));
    expect(await quemSouEu()).toEqual({ nome: "ana", papeis: ["cliente"], clienteId: "c1" });
    fetchFalso.mockResolvedValueOnce(resposta(500, { title: "Erro" }));
    await expect(quemSouEu()).rejects.toThrow("Erro");
  });

  it("sair: POST /logout com CSRF e navega para o endereço de saída do Keycloak", async () => {
    fetchFalso.mockResolvedValue(resposta(200, { redirecionar: "http://localhost:18081/sair" }));
    const navegar = vi.fn();
    await sair(navegar);
    expect(fetchFalso.mock.calls[0]![0]).toBe("/logout");
    expect(new Headers(fetchFalso.mock.calls[0]![1]!.headers).get("X-XSRF-TOKEN")).toBe("abc=123");
    expect(navegar).toHaveBeenCalledWith("http://localhost:18081/sair");
  });
});

describe("lerCookie", () => {
  it("acha o cookie pelo nome, com '=' no valor", () => {
    expect(lerCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=x%3Dy; b=2")).toBe("x=y");
    expect(lerCookie("nao", "a=1")).toBeUndefined();
  });
});
