import { chamar } from "@ledgerly/comum";

export type Situacao = "CRIADA" | "DEBITADA" | "CONCLUIDA" | "REJEITADA" | "ESTORNANDO" | "ESTORNADA" | "EM_ANALISE";

export type Transferencia = {
  id: string;
  situacao: Situacao;
  valor: string;
  descricao: string | null;
  pagador: { contaId: string; nome: string };
  recebedor: { nome: string; chave: string; tipoChave: string };
  motivo: string | null;
  contaDevolucaoId: string | null;
  tentativas: number;
  criadaEm: string;
};

export type Conta = {
  id: string;
  clienteId: string;
  agencia: string;
  numero: string;
  situacao: "ATIVA" | "BLOQUEADA" | "ENCERRADA";
  saldo: string | null;
};

export const api = {
  emAnalise: () => chamar<Transferencia[]>("/api/v1/transferencias?situacao=EM_ANALISE&limite=50"),
  transferencia: (id: string) => chamar<Transferencia>(`/api/v1/transferencias/${id}`),
  devolver: (id: string, contaDestinoId: string, justificativa: string) =>
    chamar<Transferencia>(`/api/v1/transferencias/${id}/devolucao`, { metodo: "POST", corpo: { contaDestinoId, justificativa } }),
  conta: (id: string) => chamar<Conta>(`/api/v1/contas/${id}`),
  contasDoCliente: (clienteId: string) => chamar<Conta[]>(`/api/v1/clientes/${clienteId}/contas`),
  bloquear: (id: string) => chamar<Conta>(`/api/v1/contas/${id}/bloqueio`, { metodo: "POST" }),
  desbloquear: (id: string) => chamar<Conta>(`/api/v1/contas/${id}/bloqueio`, { metodo: "DELETE" }),
};
