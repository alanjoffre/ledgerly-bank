import { chamar } from "@ledgerly/comum";

export type Situacao =
  | "CRIADA" | "APROVADA" | "RETIDA" | "DEBITADA" | "CONCLUIDA" | "REJEITADA" | "ESTORNANDO" | "ESTORNADA" | "EM_ANALISE";

export type Transferencia = {
  id: string;
  situacao: Situacao;
  valor: string;
  descricao: string | null;
  pagador: { contaId: string; nome: string };
  recebedor: { nome: string; chave: string; tipoChave: string };
  motivo: string | null;
  /** A nota do antifraude, de 0 a 1000; {@code null} quando ele não respondeu (falha segura). */
  notaDeRisco: number | null;
  contaDevolucaoId: string | null;
  tentativas: number;
  criadaEm: string;
};

/** O que o antifraude viu e decidiu (ADR-0032). A nota e os motivos vêm das regras e do modelo. */
export type Avaliacao = {
  transferenciaId: string;
  nota: number;
  decisao: "APROVAR" | "SEGURAR" | "RECUSAR";
  motivos: { codigo: string; texto: string }[];
  fatos: {
    valor: string;
    razaoDaMedia: string | null;
    pixNaUltimaHora: number;
    recebedorNovo: boolean;
    madrugada: boolean;
    idadeDaContaRecebedoraEmDias: number;
  };
  modelo: string;
  avaliadaEm: string;
};

/** O porquê em texto corrido (ADR-0035): do modelo de linguagem local, conferido número a número, ou fixo. */
export type Explicacao = {
  texto: string;
  origem: "MODELO_DE_LINGUAGEM" | "TEXTO_FIXO";
  modelo: string | null;
  explicadaEm: string;
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
  retidas: () => chamar<Transferencia[]>("/api/v1/transferencias?situacao=RETIDA&limite=50"),
  avaliacao: (id: string) => chamar<Avaliacao>(`/api/v1/avaliacoes/${id}`),
  explicacao: (id: string) => chamar<Explicacao>(`/api/v1/avaliacoes/${id}/explicacao`),
  liberar: (id: string, justificativa: string) =>
    chamar<Transferencia>(`/api/v1/transferencias/${id}/liberacao`, { metodo: "POST", corpo: { justificativa } }),
  recusar: (id: string, justificativa: string) =>
    chamar<Transferencia>(`/api/v1/transferencias/${id}/recusa`, { metodo: "POST", corpo: { justificativa } }),
  transferencia: (id: string) => chamar<Transferencia>(`/api/v1/transferencias/${id}`),
  devolver: (id: string, contaDestinoId: string, justificativa: string) =>
    chamar<Transferencia>(`/api/v1/transferencias/${id}/devolucao`, { metodo: "POST", corpo: { contaDestinoId, justificativa } }),
  conta: (id: string) => chamar<Conta>(`/api/v1/contas/${id}`),
  contasDoCliente: (clienteId: string) => chamar<Conta[]>(`/api/v1/clientes/${clienteId}/contas`),
  bloquear: (id: string) => chamar<Conta>(`/api/v1/contas/${id}/bloqueio`, { metodo: "POST" }),
  desbloquear: (id: string) => chamar<Conta>(`/api/v1/contas/${id}/bloqueio`, { metodo: "DELETE" }),
};
