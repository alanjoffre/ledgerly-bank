import { chamar, chamarComResposta } from "@ledgerly/comum";

/** Os formatos que o internet banking usa (o mesmo JSON dos serviços, via gateway). */

export type Conta = {
  id: string;
  clienteId: string;
  agencia: string;
  numero: string;
  situacao: "ATIVA" | "BLOQUEADA" | "ENCERRADA";
  /** Só em GET /v1/contas/{id}: a lista do cliente vem com null (não consulta o razão por conta). */
  saldo: string | null;
  moeda: string | null;
};

export type ItemDoExtrato = {
  sequencia: number;
  lancamentoId: string;
  operacao: string;
  tipo: "DEBITO" | "CREDITO";
  valor: string;
  saldoApos: string;
  descricao: string | null;
  mensagem: string | null;
  registradoEm: string;
  pix: { contraparte: string | null; chave: string | null; situacao: string; motivo: string | null } | null;
};

export type Extrato = {
  contaId: string;
  saldo: { saldo: string; atualizadoEm: string } | null;
  itens: ItemDoExtrato[];
  proximaPagina: number | null;
};

export type TipoChave = "CPF" | "EMAIL" | "TELEFONE" | "ALEATORIA";
export type Chave = { chave: string; tipo: TipoChave; contaId: string; titular: string; registradaEm: string };
export type ConsultaDeChave = { chave: string; tipo: TipoChave; titular: string; instituicao: string };

export type Situacao = "CRIADA" | "DEBITADA" | "CONCLUIDA" | "REJEITADA" | "ESTORNANDO" | "ESTORNADA" | "EM_ANALISE";
export const FINAIS: ReadonlySet<Situacao> = new Set(["CONCLUIDA", "REJEITADA", "ESTORNADA", "EM_ANALISE"]);

export type Transferencia = {
  id: string;
  situacao: Situacao;
  valor: string;
  descricao: string | null;
  recebedor: { nome: string; chave: string; tipoChave: TipoChave };
  motivo: string | null;
};

const caminho = (texto: string) => encodeURIComponent(texto);

export const api = {
  contasDoCliente: (clienteId: string) => chamar<Conta[]>(`/api/v1/clientes/${clienteId}/contas`),
  conta: (contaId: string) => chamar<Conta>(`/api/v1/contas/${contaId}`),
  extrato: (contaId: string, antesDe?: number) =>
    chamar<Extrato>(`/api/v1/contas/${contaId}/extrato?limite=20${antesDe ? `&antesDe=${antesDe}` : ""}`),
  chavesDaConta: (contaId: string) => chamar<Chave[]>(`/api/v1/contas/${contaId}/chaves`),
  registrarChave: (contaId: string, tipo: TipoChave, valor: string | null) =>
    chamar<Chave>("/api/v1/chaves", { metodo: "POST", corpo: { contaId, tipo, valor } }),
  excluirChave: (chave: string) => chamar<void>(`/api/v1/chaves/${caminho(chave)}`, { metodo: "DELETE" }),
  consultarChave: (chave: string) => chamar<ConsultaDeChave>(`/api/v1/chaves/${caminho(chave)}`),
  enviarPix: (idempotencia: string, pagadorContaId: string, chave: string, valor: string, descricao: string) =>
    chamarComResposta<Transferencia>("/api/v1/transferencias", {
      metodo: "POST",
      cabecalhos: { "Idempotency-Key": idempotencia },
      corpo: { pagadorContaId, chave, valor, descricao: descricao || null },
    }),
  transferencia: (id: string) => chamar<Transferencia>(`/api/v1/transferencias/${id}`),
};
