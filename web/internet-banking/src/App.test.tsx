import { fetchFalso } from "@ledgerly/comum/teste";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App.tsx";
import { EnviarPix } from "./paginas/EnviarPix.tsx";
import { Route, Routes } from "react-router";

const CLIENTE = "c0000000-0000-4000-8000-000000000001";
const CONTA = "a0000000-0000-4000-8000-000000000001";
const ANA = { nome: "ana", papeis: ["cliente"], clienteId: CLIENTE };

const conta = (extra = {}) => ({
  id: CONTA, clienteId: CLIENTE, agencia: "0001", numero: "123456-7", situacao: "ATIVA", saldo: "1500.00", moeda: "BRL", ...extra,
});

function abrir(caminho: string, elemento = <App />) {
  return render(<MemoryRouter initialEntries={[caminho]}>{elemento}</MemoryRouter>);
}

afterEach(() => vi.unstubAllGlobals());

describe("entrada", () => {
  it("sem sessão, mostra o botão Entrar que leva ao login no Keycloak", async () => {
    fetchFalso({ "GET /api/eu": { status: 401, corpo: { title: "Não autenticado" } } });
    abrir("/");
    expect(await screen.findByRole("link", { name: "Entrar" })).toHaveAttribute("href", "/oauth2/authorization/keycloak");
  });

  it("quem não é cliente vê o aviso, e nenhuma conta é pedida", async () => {
    const rede = fetchFalso({ "GET /api/eu": { corpo: { nome: "carla", papeis: ["analista"], clienteId: null } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("não é de cliente");
    expect(rede.chamadas).toHaveLength(1);
  });

  it("cliente sem conta vinculada vê o aviso", async () => {
    fetchFalso({ "GET /api/eu": { corpo: { nome: "novo", papeis: ["cliente"], clienteId: null } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("ainda não tem uma conta vinculada");
  });

  it("banco fora do ar: diz que não conseguiu falar com ele", async () => {
    fetchFalso({ "GET /api/eu": { status: 502, corpo: { title: "Bad Gateway" } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("Não foi possível falar com o banco");
  });

  it("Sair chama o /logout e vai para o endereço do Keycloak", async () => {
    fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/clientes/${CLIENTE}/contas`]: { corpo: [] },
      "POST /logout": { corpo: { redirecionar: "http://localhost:18081/sair" } },
    });
    const navegar = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign: navegar });
    abrir("/");
    await userEvent.click(await screen.findByRole("button", { name: "Sair" }));
    await vi.waitFor(() => expect(navegar).toHaveBeenCalledWith("http://localhost:18081/sair"));
  });
});

describe("minhas contas", () => {
  it("mostra cada conta com o saldo em reais e os atalhos", async () => {
    // Como a API real: a lista vem sem saldo (saldo null, para não consultar o razão uma vez por
    // conta); o saldo vem de GET /v1/contas/{id}. Com o razão fora do ar, esse responde 503.
    const semSaldo = { saldo: null, moeda: null };
    fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/clientes/${CLIENTE}/contas`]: { corpo: [conta(semSaldo), conta({ id: "outra", numero: "999", situacao: "BLOQUEADA", ...semSaldo })] },
      [`GET /api/v1/contas/${CONTA}`]: { corpo: conta() },
      "GET /api/v1/contas/outra": { status: 503, corpo: { title: "Serviço indisponível" } },
    });
    abrir("/");
    const ativa = await screen.findByRole("region", { name: "Conta 123456-7" });
    await vi.waitFor(() => expect(within(ativa).getByTestId("saldo")).toHaveTextContent("R$ 1.500,00"));
    expect(within(ativa).getByRole("link", { name: "Enviar Pix" })).toHaveAttribute("href", `/contas/${CONTA}/pix`);
    const bloqueada = screen.getByRole("region", { name: "Conta 999" });
    await vi.waitFor(() => expect(within(bloqueada).getByTestId("saldo")).toHaveTextContent("Saldo indisponível agora"));
    expect(within(bloqueada).queryByRole("link", { name: "Enviar Pix" })).toBeNull();
  });

  it("sem conta aberta, diz isso", async () => {
    fetchFalso({ "GET /api/eu": { corpo: ANA }, [`GET /api/v1/clientes/${CLIENTE}/contas`]: { corpo: [] } });
    abrir("/");
    expect(await screen.findByText("Você ainda não tem conta aberta.")).toBeInTheDocument();
  });
});

describe("extrato", () => {
  const item = (sequencia: number, extra = {}) => ({
    sequencia, lancamentoId: `l${sequencia}`, operacao: "PIX_ENVIO", tipo: "DEBITO", valor: "25.00", saldoApos: "975.00",
    moeda: "BRL", descricao: null, mensagem: "almoço", registradoEm: "2026-10-28T15:00:00Z",
    pix: { contraparte: "Bruno", chave: "bruno@exemplo.test", situacao: "CONCLUIDO", motivo: null }, ...extra,
  });

  it("lista os lançamentos com sinal e carrega os mais antigos", async () => {
    const rede = fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/contas/${CONTA}/extrato?limite=20`]: {
        corpo: { contaId: CONTA, saldo: { saldo: "975.00", atualizadoEm: "2026-10-28T15:00:00Z" }, itens: [item(2)], proximaPagina: 2 },
      },
      [`GET /api/v1/contas/${CONTA}/extrato?limite=20&antesDe=2`]: {
        corpo: { contaId: CONTA, saldo: null, itens: [item(1, { operacao: "DEPOSITO", tipo: "CREDITO", valor: "1000.00", pix: null, mensagem: null })], proximaPagina: null },
      },
    });
    abrir(`/contas/${CONTA}`);
    expect(await screen.findByTestId("saldo")).toHaveTextContent("R$ 975,00");
    expect(screen.getByText("Pix enviado · Bruno")).toBeInTheDocument();
    expect(screen.getByText(/− R\$ 25,00/)).toHaveClass("debito");
    await userEvent.click(screen.getByRole("button", { name: "Ver mais antigos" }));
    expect(await screen.findByText(/\+ R\$ 1\.000,00/)).toHaveClass("credito");
    expect(screen.queryByRole("button", { name: "Ver mais antigos" })).toBeNull();
    expect(rede.vezes(`GET /api/v1/contas/${CONTA}/extrato?limite=20&antesDe=2`)).toBe(1);
  });

  it("conta sem movimentação (404) mostra o aviso, não um erro", async () => {
    fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/contas/${CONTA}/extrato?limite=20`]: { status: 404, corpo: { title: "Não encontrado" } },
    });
    abrir(`/contas/${CONTA}`);
    expect(await screen.findByRole("status")).toHaveTextContent("Ainda não há movimentação");
  });
});

describe("enviar Pix", () => {
  // A consulta devolve a chave MASCARADA (como o serviço Pix); o envio tem de usar a que a pessoa digitou.
  const CHAVE = "bruno@exemplo.test";
  const BRUNO = { chave: "b***@exemplo.test", tipo: "EMAIL", titular: "Bruno S.", instituicao: "Ledgerly Bank" };
  const pix = (situacao: string, extra = {}) => ({
    id: "p1", situacao, valor: "25.50", descricao: "almoço", recebedor: { nome: "Bruno S.", chave: BRUNO.chave, tipoChave: "EMAIL" }, motivo: null, ...extra,
  });
  const telaDePix = () => abrir(`/contas/${CONTA}/pix`, <Routes><Route path="/contas/:contaId/pix" element={<EnviarPix intervaloMs={1} />} /></Routes>);

  async function preencherEConferir(valor = "25,50") {
    await userEvent.type(screen.getByLabelText("Chave Pix de quem recebe"), CHAVE);
    await userEvent.type(screen.getByLabelText("Valor (R$)"), valor);
    await userEvent.type(screen.getByLabelText("Mensagem (opcional)"), "almoço");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
  }

  it("confere quem recebe, envia com chave de idempotência e acompanha até concluir", async () => {
    const rede = fetchFalso({
      [`GET /api/v1/chaves/${encodeURIComponent(CHAVE)}`]: { corpo: BRUNO },
      "POST /api/v1/transferencias": { status: 202, corpo: pix("DEBITADA") },
      "GET /api/v1/transferencias/p1": [{ corpo: pix("DEBITADA") }, { corpo: pix("CONCLUIDA") }],
    });
    telaDePix();
    await preencherEConferir();
    const conferencia = await screen.findByRole("region", { name: "Conferir o Pix" });
    expect(conferencia).toHaveTextContent("Bruno S.");
    expect(conferencia).toHaveTextContent("R$ 25,50");
    await userEvent.click(screen.getByRole("button", { name: "Confirmar Pix" }));
    expect(await screen.findByText("Pix enviado.")).toBeInTheDocument();

    const envio = rede.chamadas.find((c) => c.rota === "POST /api/v1/transferencias")!;
    expect(envio.corpo).toEqual({ pagadorContaId: CONTA, chave: CHAVE, valor: "25.50", descricao: "almoço" });
    expect(envio.cabecalhos.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    expect(rede.vezes("GET /api/v1/transferencias/p1")).toBe(2);
  });

  it("se o envio falhar, tentar de novo usa a MESMA chave de idempotência", async () => {
    const rede = fetchFalso({
      [`GET /api/v1/chaves/${encodeURIComponent(CHAVE)}`]: { corpo: BRUNO },
      "POST /api/v1/transferencias": [{ status: 503, corpo: { title: "Indisponível", detail: "Tente de novo." } }, { status: 201, corpo: pix("CONCLUIDA") }],
    });
    telaDePix();
    await preencherEConferir();
    await userEvent.click(await screen.findByRole("button", { name: "Confirmar Pix" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("o mesmo Pix não é enviado duas vezes");
    await userEvent.click(screen.getByRole("button", { name: "Confirmar Pix" }));
    expect(await screen.findByText("Pix enviado.")).toBeInTheDocument();
    const [primeira, segunda] = rede.chamadas.filter((c) => c.rota === "POST /api/v1/transferencias");
    expect(segunda!.cabecalhos.get("Idempotency-Key")).toBe(primeira!.cabecalhos.get("Idempotency-Key"));
  });

  it("Pix que fica em análise diz isso à pessoa", async () => {
    fetchFalso({
      [`GET /api/v1/chaves/${encodeURIComponent(CHAVE)}`]: { corpo: BRUNO },
      "POST /api/v1/transferencias": { status: 201, corpo: pix("EM_ANALISE", { motivo: "conta do pagador encerrada" }) },
    });
    telaDePix();
    await preencherEConferir();
    await userEvent.click(await screen.findByRole("button", { name: "Confirmar Pix" }));
    expect(await screen.findByRole("status")).toHaveTextContent("em análise pela equipe de segurança");
  });

  it("valor inválido e chave inexistente não chegam a enviar", async () => {
    const rede = fetchFalso({ "GET /api/v1/chaves/ninguem%40exemplo.test": { status: 404, corpo: { title: "Não encontrada" } } });
    telaDePix();
    await userEvent.type(screen.getByLabelText("Chave Pix de quem recebe"), "ninguem@exemplo.test");
    await userEvent.type(screen.getByLabelText("Valor (R$)"), "abc");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Digite um valor como 10,50.");
    expect(rede.chamadas).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText("Valor (R$)"));
    await userEvent.type(screen.getByLabelText("Valor (R$)"), "5");
    await userEvent.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Chave Pix não encontrada.");
  });

  it("Corrigir volta ao formulário", async () => {
    fetchFalso({ [`GET /api/v1/chaves/${encodeURIComponent(CHAVE)}`]: { corpo: BRUNO } });
    telaDePix();
    await preencherEConferir();
    await userEvent.click(await screen.findByRole("button", { name: "Corrigir" }));
    expect(screen.getByRole("button", { name: "Continuar" })).toBeInTheDocument();
  });
});

describe("minhas chaves", () => {
  const chave = (valor: string, tipo = "EMAIL") => ({ chave: valor, tipo, contaId: CONTA, titular: "Ana", registradaEm: "2026-10-28T12:00:00Z" });

  it("lista, registra (aleatória vai sem valor) e exclui", async () => {
    const rede = fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/contas/${CONTA}/chaves`]: [{ corpo: [chave("ana@exemplo.test")] }, { corpo: [chave("ana@exemplo.test"), chave("7d1c-aleatoria", "ALEATORIA")] }, { corpo: [chave("7d1c-aleatoria", "ALEATORIA")] }],
      "POST /api/v1/chaves": { status: 201, corpo: chave("7d1c-aleatoria", "ALEATORIA") },
      "DELETE /api/v1/chaves/ana%40exemplo.test": { status: 204 },
    });
    abrir(`/contas/${CONTA}/chaves`);
    expect(await screen.findByText("ana@exemplo.test")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Tipo"), "ALEATORIA");
    expect(screen.queryByLabelText("Chave aleatória")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Registrar chave" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Chave 7d1c-aleatoria registrada.");
    expect(rede.chamadas.find((c) => c.rota === "POST /api/v1/chaves")!.corpo).toEqual({ contaId: CONTA, tipo: "ALEATORIA", valor: null });

    await userEvent.click(screen.getByRole("button", { name: "Excluir ana@exemplo.test" }));
    expect(await screen.findByText("Chave ana@exemplo.test excluída.")).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByRole("cell", { name: "ana@exemplo.test" })).toBeNull());
  });

  it("erro ao registrar aparece para a pessoa", async () => {
    fetchFalso({
      "GET /api/eu": { corpo: ANA },
      [`GET /api/v1/contas/${CONTA}/chaves`]: { corpo: [] },
      "POST /api/v1/chaves": { status: 409, corpo: { title: "Chave já registrada", detail: "Esta chave já está em uso." } },
    });
    abrir(`/contas/${CONTA}/chaves`);
    expect(await screen.findByText("Nenhuma chave nesta conta.")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("E-mail"), "ana@exemplo.test");
    await userEvent.click(screen.getByRole("button", { name: "Registrar chave" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Esta chave já está em uso.");
  });
});
