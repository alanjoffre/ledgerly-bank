import { ErroDaApi, formatarReais } from "@ledgerly/comum";
import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router";
import { api, type Conta } from "../api.ts";

/** Consultar uma conta pelo id e bloquear ou desbloquear (por suspeita de fraude, por exemplo). */
export function ConsultaDeConta() {
  const { contaId } = useParams();
  const navegar = useNavigate();
  const [busca, setBusca] = useState(contaId ?? "");
  const [conta, setConta] = useState<Conta | null>(null);
  const [mensagem, setMensagem] = useState<{ classe: string; texto: string } | null>(null);
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    setConta(null);
    setMensagem(null);
    if (!contaId) return;
    setBusca(contaId);
    api.conta(contaId).then(setConta, (e: unknown) =>
      setMensagem({ classe: "erro", texto: e instanceof ErroDaApi && e.status === 404 ? "Conta não encontrada." : (e as Error).message }));
  }, [contaId]);

  function buscar(evento: FormEvent) {
    evento.preventDefault();
    if (busca.trim()) navegar(`/contas/${busca.trim()}`);
  }

  async function mudar(acao: "bloquear" | "desbloquear") {
    if (!conta) return;
    setOcupado(true);
    try {
      const nova = await api[acao](conta.id);
      setConta(nova);
      setMensagem({ classe: "ok", texto: acao === "bloquear" ? "Conta bloqueada." : "Conta desbloqueada." });
    } catch (e) {
      setMensagem({ classe: "erro", texto: (e as Error).message });
    } finally {
      setOcupado(false);
    }
  }

  return (
    <>
      <h1>Contas</h1>
      <form className="cartao" onSubmit={buscar}>
        <label htmlFor="busca">Id da conta</label>
        <input id="busca" value={busca} onChange={(e) => setBusca(e.target.value)} autoComplete="off" />
        <div className="acoes"><button type="submit">Consultar</button></div>
      </form>
      {mensagem && <p className={mensagem.classe} role="status">{mensagem.texto}</p>}
      {conta && (
        <section className="cartao" aria-label="A conta">
          <p>Agência {conta.agencia} · Conta {conta.numero} · <span className="selo" data-testid="situacao">{conta.situacao}</span></p>
          <p className="valor saldo">{conta.saldo === null ? "Saldo indisponível" : formatarReais(conta.saldo)}</p>
          <div className="acoes">
            {conta.situacao === "ATIVA" && <button className="perigo" onClick={() => void mudar("bloquear")} disabled={ocupado}>Bloquear</button>}
            {conta.situacao === "BLOQUEADA" && <button onClick={() => void mudar("desbloquear")} disabled={ocupado}>Desbloquear</button>}
          </div>
        </section>
      )}
    </>
  );
}
