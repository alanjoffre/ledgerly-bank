import { ErroDaApi, formatarDataHora, formatarReais } from "@ledgerly/comum";
import { useEffect, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api, type Conta, type Transferencia } from "../api.ts";

const MINIMO = 10;

/**
 * A decisão da analista sobre um Pix em análise: devolver o dinheiro a outra conta do MESMO
 * titular (a conta de origem foi encerrada, por isso a Saga não conseguiu estornar). O serviço
 * de Pix confere tudo de novo — titular, conta ativa, situação — e grava quem decidiu (ADR-0030).
 */
export function Decisao() {
  const { id = "" } = useParams();
  const [pix, setPix] = useState<Transferencia | null>(null);
  const [contas, setContas] = useState<Conta[] | null>(null);
  const [destino, setDestino] = useState("");
  const [justificativa, setJustificativa] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [feito, setFeito] = useState<Transferencia | null>(null);
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    (async () => {
      const t = await api.transferencia(id);
      setPix(t);
      const pagador = await api.conta(t.pagador.contaId);
      const doTitular = await api.contasDoCliente(pagador.clienteId);
      setContas(doTitular);
      setDestino(doTitular.find((c) => c.situacao === "ATIVA")?.id ?? "");
    })().catch((e: Error) => setErro(e.message));
  }, [id]);

  async function devolver(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setOcupado(true);
    try {
      setFeito(await api.devolver(id, destino, justificativa.trim()));
    } catch (e) {
      setErro(e instanceof ErroDaApi && e.status === 409 ? `Este Pix já foi decidido ou saiu da análise. ${e.message}` : (e as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  const ativas = contas?.filter((c) => c.situacao === "ATIVA") ?? [];
  const valida = justificativa.trim().length >= MINIMO && destino !== "";

  return (
    <>
      <p><Link to="/">← Fila de análise</Link></p>
      <h1>Analisar Pix</h1>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {!pix && !erro && <p aria-busy="true">Carregando…</p>}
      {pix && (
        <section className="cartao" aria-label="O Pix">
          <p className="valor saldo">{formatarReais(pix.valor)}</p>
          <p>De <strong>{pix.pagador.nome}</strong> para <strong>{pix.recebedor.nome}</strong> ({pix.recebedor.chave})</p>
          <p className="suave">Criado em {formatarDataHora(pix.criadaEm)} · {pix.tentativas} tentativa(s) · <span className="selo">{pix.situacao}</span></p>
          {pix.motivo && <p className="aviso">{pix.motivo}</p>}
          <p><Link to={`/contas/${pix.pagador.contaId}`}>Ver a conta do pagador</Link></p>
        </section>
      )}

      {feito ? (
        <p className="ok" role="status">
          Devolução registrada. O Pix agora está {feito.situacao === "ESTORNADA" ? "devolvido" : "sendo devolvido"} ({feito.situacao}).{" "}
          <Link to="/">Voltar à fila</Link>
        </p>
      ) : pix?.situacao === "EM_ANALISE" && contas && (
        <form className="cartao" onSubmit={(e) => void devolver(e)}>
          <h2>Devolver ao titular</h2>
          {ativas.length === 0 ? (
            <p className="aviso">O titular não tem conta ativa para receber a devolução.</p>
          ) : (
            <>
              <label htmlFor="destino">Conta que recebe a devolução</label>
              <select id="destino" value={destino} onChange={(e) => setDestino(e.target.value)}>
                {contas.map((c) => (
                  <option key={c.id} value={c.id} disabled={c.situacao !== "ATIVA"}>
                    {c.agencia} / {c.numero} — {c.situacao}
                  </option>
                ))}
              </select>
              <label htmlFor="justificativa">Justificativa (mín. {MINIMO} caracteres; fica na auditoria)</label>
              <textarea id="justificativa" value={justificativa} onChange={(e) => setJustificativa(e.target.value)} maxLength={500} />
              <div className="acoes"><button type="submit" disabled={!valida || ocupado}>Devolver</button></div>
            </>
          )}
        </form>
      )}
    </>
  );
}
