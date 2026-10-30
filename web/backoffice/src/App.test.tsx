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

describe("Pix retidos", () => {
  const retido = (extra = {}) => pix({
    situacao: "RETIDA", valor: "4800.00", notaDeRisco: 770, tentativas: 0,
    motivo: "Retido pelo antifraude (nota 770): valor de R$ 4.800,00; 15,0 vezes a média dos Pix anteriores do pagador", ...extra,
  });
  const avaliacao = {
    transferenciaId: PIX, nota: 770, decisao: "SEGURAR", modelo: "risco-pix@0123456789ab", avaliadaEm: "2026-10-28T13:00:00Z",
    motivos: [
      { codigo: "modelo:fora-do-costume", texto: "15,0 vezes a média dos Pix anteriores do pagador" },
      { codigo: "modelo:recebedor-novo", texto: "primeiro Pix do pagador para esta conta" },
    ],
    fatos: { valor: "4800.00", razaoDaMedia: "15", pixNaUltimaHora: 1, recebedorNovo: true, madrugada: false, idadeDaContaRecebedoraEmDias: 3 },
  };
  const rotas = (extra = {}) => ({
    "GET /api/eu": { corpo: CARLA },
    [`GET /api/v1/transferencias/${PIX}`]: { corpo: retido() },
    [`GET /api/v1/avaliacoes/${PIX}`]: { corpo: avaliacao },
    [`GET /api/v1/avaliacoes/${PIX}/explicacao`]: { corpo: {
      texto: "O valor é 15,0 vezes a média do pagador e vai para uma conta que ele nunca pagou.",
      origem: "MODELO_DE_LINGUAGEM", modelo: "gemma3:4b", explicadaEm: "2026-10-28T13:00:05Z",
    } },
    ...extra,
  });
  const JUSTIFICATIVA = "Cliente confirmou o Pix por telefone.";

  it("a fila mostra a nota; o Pix sem nota (antifraude fora do ar) aparece como 'sem nota'", async () => {
    fetchFalso({
      "GET /api/eu": { corpo: CARLA },
      "GET /api/v1/transferencias?situacao=RETIDA&limite=50": { corpo: [retido(), retido({ id: "outro", notaDeRisco: null, pagador: { contaId: PAGADORA, nome: "Caio" } })] },
    });
    abrir("/retidos");
    const tabela = await screen.findByRole("table", { name: "Pix retidos" });
    expect(tabela).toHaveTextContent("770");
    expect(tabela).toHaveTextContent("R$ 4.800,00");
    expect(tabela).toHaveTextContent("sem nota");
    expect(within(tabela).getByRole("link", { name: "Analisar o Pix retido de Ana" })).toHaveAttribute("href", `/retidos/${PIX}`);
  });

  it("fila cheia: avisa que pode haver mais", async () => {
    const cheia = Array.from({ length: 50 }, (_, i) => retido({ id: `pix-${i}` }));
    fetchFalso(rotas({ "GET /api/v1/transferencias?situacao=RETIDA&limite=50": { corpo: cheia } }));
    abrir("/retidos");
    expect(await screen.findByRole("note")).toHaveTextContent("Mostrando os 50 mais antigos");
  });

  it("fila menor que o limite não avisa", async () => {
    fetchFalso(rotas({ "GET /api/v1/transferencias?situacao=RETIDA&limite=50": { corpo: [retido()] } }));
    abrir("/retidos");
    await screen.findByRole("table", { name: "Pix retidos" });
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("fila vazia", async () => {
    fetchFalso({ "GET /api/eu": { corpo: CARLA }, "GET /api/v1/transferencias?situacao=RETIDA&limite=50": { corpo: [] } });
    abrir("/retidos");
    expect(await screen.findByRole("status")).toHaveTextContent("Nenhum Pix retido.");
  });

  it("mostra por que foi retido: nota, motivos e os fatos que o antifraude viu", async () => {
    fetchFalso(rotas());
    abrir(`/retidos/${PIX}`);
    const porQue = await screen.findByRole("region", { name: "Por que foi retido" });
    expect(porQue).toHaveTextContent("Nota 770 de 1000");
    expect(within(porQue).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "15,0 vezes a média dos Pix anteriores do pagador", "primeiro Pix do pagador para esta conta",
    ]);
    const fatos = within(porQue).getByRole("table", { name: "O que o antifraude viu" });
    expect(fatos).toHaveTextContent("15,0 vezes a média");
    expect(fatos).toHaveTextContent("Já pagou esta conta antesnão");
    expect(fatos).toHaveTextContent("3 dias");

    const explicacao = await within(porQue).findByRole("figure", { name: "Explicação" });
    expect(explicacao).toHaveTextContent("vai para uma conta que ele nunca pagou");
    expect(explicacao).toHaveTextContent("modelo de linguagem local (gemma3:4b) e conferida número a número");
  });

  it("explicação em texto fixo diz que é fixo; explicação que falhou some, e o resto fica", async () => {
    fetchFalso(rotas({ [`GET /api/v1/avaliacoes/${PIX}/explicacao`]: { corpo: {
      texto: "Nota 770 de 1000: o Pix foi retido para uma pessoa analisar.", origem: "TEXTO_FIXO", modelo: null, explicadaEm: "2026-10-28T13:00:05Z",
    } } }));
    const { unmount } = abrir(`/retidos/${PIX}`);
    expect(await screen.findByRole("figure", { name: "Explicação" })).toHaveTextContent("Texto fixo, montado com os motivos");
    unmount();

    fetchFalso(rotas({ [`GET /api/v1/avaliacoes/${PIX}/explicacao`]: { status: 503, corpo: { detail: "fora do ar" } } }));
    abrir(`/retidos/${PIX}`);
    expect(await screen.findByRole("region", { name: "Por que foi retido" })).toHaveTextContent("Nota 770 de 1000");
    await vi.waitFor(() => expect(screen.queryByText("Escrevendo a explicação…")).toBeNull());
    expect(screen.queryByRole("figure")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("libera com justificativa: o Pix segue", async () => {
    const rede = fetchFalso(rotas({ [`POST /api/v1/transferencias/${PIX}/liberacao`]: { corpo: retido({ situacao: "CONCLUIDA" }) } }));
    abrir(`/retidos/${PIX}`);
    const liberar = await screen.findByRole("button", { name: "Liberar" });
    await userEvent.type(screen.getByLabelText(/Justificativa/), "curta");
    expect(liberar).toBeDisabled();
    expect(screen.getByRole("button", { name: "Recusar" })).toBeDisabled();
    await userEvent.clear(screen.getByLabelText(/Justificativa/));
    await userEvent.type(screen.getByLabelText(/Justificativa/), JUSTIFICATIVA);
    await userEvent.click(liberar);

    expect(await screen.findByRole("status")).toHaveTextContent("Pix liberado. Agora está CONCLUIDA.");
    expect(rede.chamadas.find((c) => c.rota.startsWith("POST"))!.corpo).toEqual({ justificativa: JUSTIFICATIVA });
    expect(rede.vezes(`POST /api/v1/transferencias/${PIX}/recusa`)).toBe(0);
  });

  it("recusa: nenhum dinheiro sai", async () => {
    const rede = fetchFalso(rotas({ [`POST /api/v1/transferencias/${PIX}/recusa`]: { corpo: retido({ situacao: "REJEITADA" }) } }));
    abrir(`/retidos/${PIX}`);
    await userEvent.type(await screen.findByLabelText(/Justificativa/), "Cliente não reconhece: golpe do falso parente.");
    await userEvent.click(screen.getByRole("button", { name: "Recusar" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Pix recusado. Nenhum dinheiro saiu.");
    expect(rede.vezes(`POST /api/v1/transferencias/${PIX}/recusa`)).toBe(1);
  });

  it("outra analista decidiu antes (409): diz isso", async () => {
    fetchFalso(rotas({ [`POST /api/v1/transferencias/${PIX}/liberacao`]: { status: 409, corpo: { detail: "A transferência não está retida." } } }));
    abrir(`/retidos/${PIX}`);
    await userEvent.type(await screen.findByLabelText(/Justificativa/), JUSTIFICATIVA);
    await userEvent.click(screen.getByRole("button", { name: "Liberar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Este Pix já foi decidido ou não está mais retido.");
  });

  it("sem avaliação (o antifraude não respondeu): avisa, e a decisão continua possível", async () => {
    fetchFalso(rotas({
      [`GET /api/v1/transferencias/${PIX}`]: { corpo: retido({ notaDeRisco: null, motivo: "Retido sem avaliação: o valor passa de R$ 500,00 e o antifraude não respondeu (HTTP 503)" }) },
      [`GET /api/v1/avaliacoes/${PIX}`]: { status: 404, corpo: { detail: "Avaliação não encontrada" } },
    }));
    abrir(`/retidos/${PIX}`);
    expect(await screen.findByText(/O antifraude não respondeu a tempo/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("button", { name: "Liberar" })).toBeInTheDocument();
  });

  it("Pix que não está retido não mostra a decisão", async () => {
    fetchFalso(rotas({ [`GET /api/v1/transferencias/${PIX}`]: { corpo: retido({ situacao: "CONCLUIDA" }) } }));
    abrir(`/retidos/${PIX}`);
    expect(await screen.findByRole("region", { name: "O Pix" })).toHaveTextContent("CONCLUIDA");
    expect(screen.queryByRole("button", { name: "Liberar" })).toBeNull();
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
