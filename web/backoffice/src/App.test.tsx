import { fetchFalso } from "@ledgerly/comum/teste";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App.tsx";

const CARLA = { nome: "carla", papeis: ["analista"], clienteId: null };
const PIX = "p0000000-0000-4000-8000-000000000001";
const PAGADORA = "a0000000-0000-4000-8000-000000000001";
const POUPANCA = "a0000000-0000-4000-8000-000000000002";
const TITULAR = "c0000000-0000-4000-8000-000000000001";

const pix = (extra = {}) => ({
  id: PIX, situacao: "EM_ANALISE", valor: "300.00", descricao: null,
  pagador: { contaId: PAGADORA, nome: "Ana" }, recebedor: { nome: "Bruno", chave: "bruno@exemplo.test", tipoChave: "EMAIL" },
  motivo: "Estorno recusado: a conta do pagador foi encerrada", contaDevolucaoId: null, tentativas: 5, criadaEm: "2026-10-28T13:00:00Z", ...extra,
});
const conta = (id: string, situacao: string, numero: string) => ({ id, clienteId: TITULAR, agencia: "0001", numero, situacao, saldo: "0.00" });

function abrir(caminho: string) {
  return render(<MemoryRouter initialEntries={[caminho]}><App /></MemoryRouter>);
}

afterEach(() => vi.unstubAllGlobals());

describe("entrada", () => {
  it("sem sessão: botão Entrar", async () => {
    fetchFalso({ "GET /api/eu": { status: 401, corpo: {} } });
    abrir("/");
    expect(await screen.findByRole("link", { name: "Entrar" })).toHaveAttribute("href", "/oauth2/authorization/keycloak");
  });

  it("cliente no backoffice: acesso negado, e a fila não é pedida", async () => {
    const rede = fetchFalso({ "GET /api/eu": { corpo: { nome: "ana", papeis: ["cliente"], clienteId: TITULAR } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("Acesso negado");
    expect(rede.chamadas).toHaveLength(1);
  });

  it("falha do banco aparece", async () => {
    fetchFalso({ "GET /api/eu": { status: 500, corpo: { title: "Erro interno" } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("Erro interno");
  });
});

describe("fila de análise", () => {
  it("lista os Pix em análise com valor, pagador e motivo", async () => {
    fetchFalso({ "GET /api/eu": { corpo: CARLA }, "GET /api/v1/transferencias?situacao=EM_ANALISE&limite=50": { corpo: [pix()] } });
    abrir("/");
    const tabela = await screen.findByRole("table", { name: "Pix em análise" });
    expect(tabela).toHaveTextContent("R$ 300,00");
    expect(tabela).toHaveTextContent("a conta do pagador foi encerrada");
    expect(within(tabela).getByRole("link", { name: "Analisar o Pix de Ana" })).toHaveAttribute("href", `/transferencias/${PIX}`);
  });

  it("fila vazia", async () => {
    fetchFalso({ "GET /api/eu": { corpo: CARLA }, "GET /api/v1/transferencias?situacao=EM_ANALISE&limite=50": { corpo: [] } });
    abrir("/");
    expect(await screen.findByRole("status")).toHaveTextContent("Nenhum Pix esperando análise.");
  });

  it("erro ao carregar a fila", async () => {
    fetchFalso({ "GET /api/eu": { corpo: CARLA }, "GET /api/v1/transferencias?situacao=EM_ANALISE&limite=50": { status: 503, corpo: { detail: "Pix fora do ar" } } });
    abrir("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("Pix fora do ar");
  });
});

describe("decisão", () => {
  const rotas = (extra = {}) => ({
    "GET /api/eu": { corpo: CARLA },
    [`GET /api/v1/transferencias/${PIX}`]: { corpo: pix() },
    [`GET /api/v1/contas/${PAGADORA}`]: { corpo: conta(PAGADORA, "ENCERRADA", "111") },
    [`GET /api/v1/clientes/${TITULAR}/contas`]: { corpo: [conta(PAGADORA, "ENCERRADA", "111"), conta(POUPANCA, "ATIVA", "222")] },
    ...extra,
  });

  it("devolve para a conta ativa do mesmo titular, com justificativa", async () => {
    const rede = fetchFalso(rotas({ [`POST /api/v1/transferencias/${PIX}/devolucao`]: { corpo: pix({ situacao: "ESTORNADA", contaDevolucaoId: POUPANCA }) } }));
    abrir(`/transferencias/${PIX}`);
    const destino = await screen.findByLabelText("Conta que recebe a devolução");
    expect(destino).toHaveValue(POUPANCA);
    expect(within(destino).getByRole("option", { name: /111 — ENCERRADA/ })).toBeDisabled();

    const botao = screen.getByRole("button", { name: "Devolver" });
    await userEvent.type(screen.getByLabelText(/Justificativa/), "curta");
    expect(botao).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Justificativa/), " — titular confirmou por telefone");
    await userEvent.click(botao);

    expect(await screen.findByRole("status")).toHaveTextContent("Devolução registrada. O Pix agora está devolvido (ESTORNADA).");
    expect(rede.chamadas.find((c) => c.rota.startsWith("POST"))!.corpo).toEqual({
      contaDestinoId: POUPANCA, justificativa: "curta — titular confirmou por telefone",
    });
  });

  it("outra analista decidiu antes (409): diz isso", async () => {
    fetchFalso(rotas({ [`POST /api/v1/transferencias/${PIX}/devolucao`]: { status: 409, corpo: { detail: "A transferência não está em análise." } } }));
    abrir(`/transferencias/${PIX}`);
    await userEvent.type(await screen.findByLabelText(/Justificativa/), "golpe confirmado pelo titular");
    await userEvent.click(screen.getByRole("button", { name: "Devolver" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Este Pix já foi decidido ou saiu da análise.");
  });

  it("titular sem conta ativa: não há como devolver", async () => {
    fetchFalso(rotas({ [`GET /api/v1/clientes/${TITULAR}/contas`]: { corpo: [conta(PAGADORA, "ENCERRADA", "111")] } }));
    abrir(`/transferencias/${PIX}`);
    expect(await screen.findByText("O titular não tem conta ativa para receber a devolução.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Devolver" })).toBeNull();
  });

  it("Pix que não está em análise não mostra o formulário", async () => {
    fetchFalso(rotas({ [`GET /api/v1/transferencias/${PIX}`]: { corpo: pix({ situacao: "CONCLUIDA", motivo: null }) } }));
    abrir(`/transferencias/${PIX}`);
    expect(await screen.findByRole("region", { name: "O Pix" })).toHaveTextContent("CONCLUIDA");
    expect(screen.queryByRole("heading", { name: "Devolver ao titular" })).toBeNull();
  });

  it("Pix inexistente: mostra o erro", async () => {
    fetchFalso(rotas({ [`GET /api/v1/transferencias/${PIX}`]: { status: 404, corpo: { detail: "Transferência não encontrada" } } }));
    abrir(`/transferencias/${PIX}`);
    expect(await screen.findByRole("alert")).toHaveTextContent("Transferência não encontrada");
  });
});

describe("contas", () => {
  it("consulta, bloqueia e desbloqueia", async () => {
    const rede = fetchFalso({
      "GET /api/eu": { corpo: CARLA },
      [`GET /api/v1/contas/${POUPANCA}`]: { corpo: conta(POUPANCA, "ATIVA", "222") },
      [`POST /api/v1/contas/${POUPANCA}/bloqueio`]: { corpo: conta(POUPANCA, "BLOQUEADA", "222") },
      [`DELETE /api/v1/contas/${POUPANCA}/bloqueio`]: { corpo: conta(POUPANCA, "ATIVA", "222") },
    });
    abrir("/contas");
    await userEvent.type(await screen.findByLabelText("Id da conta"), POUPANCA);
    await userEvent.click(screen.getByRole("button", { name: "Consultar" }));
    expect(await screen.findByTestId("situacao")).toHaveTextContent("ATIVA");

    await userEvent.click(screen.getByRole("button", { name: "Bloquear" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Conta bloqueada.");
    expect(screen.getByTestId("situacao")).toHaveTextContent("BLOQUEADA");

    await userEvent.click(screen.getByRole("button", { name: "Desbloquear" }));
    expect(await screen.findByText("Conta desbloqueada.")).toBeInTheDocument();
    expect(rede.vezes(`POST /api/v1/contas/${POUPANCA}/bloqueio`)).toBe(1);
    expect(rede.vezes(`DELETE /api/v1/contas/${POUPANCA}/bloqueio`)).toBe(1);
  });

  it("conta inexistente (ou de fora): 404 vira 'não encontrada'", async () => {
    fetchFalso({ "GET /api/eu": { corpo: CARLA }, "GET /api/v1/contas/nao-existe": { status: 404, corpo: {} } });
    abrir("/contas/nao-existe");
    expect(await screen.findByRole("status")).toHaveTextContent("Conta não encontrada.");
  });

  it("erro ao bloquear aparece", async () => {
    fetchFalso({
      "GET /api/eu": { corpo: CARLA },
      [`GET /api/v1/contas/${POUPANCA}`]: { corpo: conta(POUPANCA, "ATIVA", "222") },
      [`POST /api/v1/contas/${POUPANCA}/bloqueio`]: { status: 409, corpo: { detail: "A conta já está bloqueada." } },
    });
    abrir(`/contas/${POUPANCA}`);
    await userEvent.click(await screen.findByRole("button", { name: "Bloquear" }));
    expect(await screen.findByRole("status")).toHaveTextContent("A conta já está bloqueada.");
  });
});
