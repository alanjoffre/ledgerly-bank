import { vi } from "vitest";

/**
 * Um fetch de mentira para os testes de tela: cada rota ("GET /api/eu") responde o que o teste
 * mandar. Rota não prevista falha o teste — assim nenhuma chamada passa sem ser vista.
 */
export type Resposta = { status?: number; corpo?: unknown; cabecalhos?: Record<string, string> };
type Responder = Resposta | ((pedido: { url: string; corpo: unknown; cabecalhos: Headers }) => Resposta);

export function fetchFalso(rotas: Record<string, Responder | Responder[]>) {
  const usos = new Map<string, number>();
  const chamadas: { rota: string; corpo: unknown; cabecalhos: Headers }[] = [];
  const falso = vi.fn(async (entrada: RequestInfo | URL, pedido?: RequestInit) => {
    const url = String(entrada);
    const rota = `${pedido?.method ?? "GET"} ${url}`;
    const corpo = typeof pedido?.body === "string" ? JSON.parse(pedido.body) : undefined;
    const cabecalhos = new Headers(pedido?.headers);
    chamadas.push({ rota, corpo, cabecalhos });
    const previsto = rotas[rota];
    if (previsto === undefined) {
      throw new Error(`Rota não prevista no teste: ${rota}`);
    }
    // Uma lista responde em ordem; a última se repete.
    const lista = Array.isArray(previsto) ? previsto : [previsto];
    const vez = usos.get(rota) ?? 0;
    usos.set(rota, vez + 1);
    const responder = lista[Math.min(vez, lista.length - 1)]!;
    const r = typeof responder === "function" ? responder({ url, corpo, cabecalhos }) : responder;
    return new Response(r.corpo === undefined ? null : JSON.stringify(r.corpo), {
      status: r.status ?? 200,
      headers: { "Content-Type": "application/json", ...r.cabecalhos },
    });
  });
  vi.stubGlobal("fetch", falso);
  return { chamadas, vezes: (rota: string) => chamadas.filter((c) => c.rota === rota).length };
}
