import { ErroDaApi, formatarReais, lerValorDigitado } from "@ledgerly/comum";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api, FINAIS, type ConsultaDeChave, type Transferencia } from "../api.ts";

type Etapa =
  | { tipo: "preencher" }
  // "chave" é a que a pessoa digitou: a consulta devolve a chave mascarada (b***@...), que só serve para mostrar.
  | { tipo: "confirmar"; chave: string; destino: ConsultaDeChave; valor: string; descricao: string; idempotencia: string }
  | { tipo: "acompanhar"; transferencia: Transferencia };

const RESULTADO: Record<string, { classe: string; texto: string }> = {
  CONCLUIDA: { classe: "ok", texto: "Pix enviado." },
  REJEITADA: { classe: "erro", texto: "Pix recusado." },
  RETIDA: { classe: "aviso", texto: "Pix retido para análise de segurança." },
  ESTORNADA: { classe: "aviso", texto: "Pix não concluído: o valor voltou para a sua conta." },
  EM_ANALISE: { classe: "aviso", texto: "Pix em análise pela equipe de segurança. Você será avisado." },
};

/**
 * Enviar Pix em três passos: preencher → conferir quem recebe → acompanhar até o fim.
 *
 * A chave de idempotência nasce na hora de conferir e é a MESMA em toda nova tentativa do mesmo
 * envio: se a rede cair depois do clique, tentar de novo não manda o Pix duas vezes (ADR-0010).
 */
export function EnviarPix({ intervaloMs = 700 }: { intervaloMs?: number }) {
  const { contaId = "" } = useParams();
  const [etapa, setEtapa] = useState<Etapa>({ tipo: "preencher" });
  const [chave, setChave] = useState("");
  const [valor, setValor] = useState("");
  const [descricao, setDescricao] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const ativo = useRef(true);

  useEffect(() => () => { ativo.current = false; }, []);

  async function conferir(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    const valorDaApi = lerValorDigitado(valor);
    if (!valorDaApi) {
      setErro("Digite um valor como 10,50.");
      return;
    }
    setOcupado(true);
    try {
      const digitada = chave.trim();
      const destino = await api.consultarChave(digitada);
      setEtapa({ tipo: "confirmar", chave: digitada, destino, valor: valorDaApi, descricao: descricao.trim(), idempotencia: crypto.randomUUID() });
    } catch (e) {
      setErro(e instanceof ErroDaApi && e.status === 404 ? "Chave Pix não encontrada." : (e as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  async function acompanhar(transferencia: Transferencia) {
    let atual = transferencia;
    setEtapa({ tipo: "acompanhar", transferencia: atual });
    while (!FINAIS.has(atual.situacao) && ativo.current) {
      await new Promise((r) => setTimeout(r, intervaloMs));
      atual = await api.transferencia(atual.id);
      if (ativo.current) setEtapa({ tipo: "acompanhar", transferencia: atual });
    }
  }

  async function confirmar() {
    if (etapa.tipo !== "confirmar") return;
    setErro(null);
    setOcupado(true);
    try {
      const { corpo } = await api.enviarPix(etapa.idempotencia, contaId, etapa.chave, etapa.valor, etapa.descricao);
      await acompanhar(corpo);
    } catch (e) {
      setErro(`${(e as Error).message} Você pode tentar de novo: o mesmo Pix não é enviado duas vezes.`);
    } finally {
      setOcupado(false);
    }
  }

  return (
    <>
      <p><Link to="/">← Minhas contas</Link></p>
      <h1>Enviar Pix</h1>
      {erro && <p className="erro" role="alert">{erro}</p>}

      {etapa.tipo === "preencher" && (
        <form className="cartao" onSubmit={(e) => void conferir(e)}>
          <label htmlFor="chave">Chave Pix de quem recebe</label>
          <input id="chave" value={chave} onChange={(e) => setChave(e.target.value)} required autoComplete="off" />
          <label htmlFor="valor">Valor (R$)</label>
          <input id="valor" value={valor} onChange={(e) => setValor(e.target.value)} required inputMode="decimal" placeholder="0,00" />
          <label htmlFor="descricao">Mensagem (opcional)</label>
          <input id="descricao" value={descricao} onChange={(e) => setDescricao(e.target.value)} maxLength={140} />
          <div className="acoes"><button type="submit" disabled={ocupado}>Continuar</button></div>
        </form>
      )}

      {etapa.tipo === "confirmar" && (
        <section className="cartao" aria-label="Conferir o Pix">
          <p>Para <strong>{etapa.destino.titular}</strong> <span className="suave">({etapa.destino.instituicao})</span></p>
          <p className="valor saldo">{formatarReais(etapa.valor)}</p>
          {etapa.descricao && <p className="suave">“{etapa.descricao}”</p>}
          <div className="acoes">
            <button onClick={() => void confirmar()} disabled={ocupado}>{ocupado ? "Enviando…" : "Confirmar Pix"}</button>
            <button className="secundario" onClick={() => setEtapa({ tipo: "preencher" })} disabled={ocupado}>Corrigir</button>
          </div>
        </section>
      )}

      {etapa.tipo === "acompanhar" && (
        <section className="cartao" aria-live="polite">
          {FINAIS.has(etapa.transferencia.situacao) ? (
            <p className={RESULTADO[etapa.transferencia.situacao]!.classe} role="status">
              {RESULTADO[etapa.transferencia.situacao]!.texto}
              {etapa.transferencia.motivo && <><br /><span className="suave">{etapa.transferencia.motivo}</span></>}
            </p>
          ) : (
            <p role="status" aria-busy="true">Processando o Pix…</p>
          )}
          <p>{formatarReais(etapa.transferencia.valor)} para {etapa.transferencia.recebedor.nome}</p>
          <div className="acoes"><Link className="botao secundario" to={`/contas/${contaId}`}>Ver extrato</Link></div>
        </section>
      )}
    </>
  );
}
