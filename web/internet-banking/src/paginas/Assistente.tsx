import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { api, type RespostaDoAssistente } from "../api.ts";

/** O mesmo limite do guardrail de entrada do serviço: a tela avisa antes de mandar. */
const TAMANHO_MAXIMO = 500;

const SUGESTOES = ["Qual é o meu saldo?", "Quanto gastei nos últimos 30 dias?", "Por que um Pix fica retido?"];

type Fala = { id: number; pergunta: string; resposta: RespostaDoAssistente | null };

/**
 * "Pergunte ao seu extrato" (ADR-0036): o assistente lê o saldo e os lançamentos das contas da
 * própria pessoa e os artigos de ajuda do banco. Ele só lê: não faz Pix nem mexe em nada.
 *
 * A conversa fica só na tela; cada pergunta vai sozinha ao serviço, que não guarda histórico.
 */
export function Assistente() {
  const [falas, setFalas] = useState<Fala[]>([]);
  const [pergunta, setPergunta] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const ativo = useRef(true);
  const proximoId = useRef(1);

  useEffect(() => () => { ativo.current = false; }, []);

  const esperando = falas.some((f) => f.resposta === null);

  async function perguntar(texto: string) {
    const limpa = texto.trim();
    if (!limpa || esperando) return;
    setErro(null);
    const id = proximoId.current++;
    setFalas((atual) => [...atual, { id, pergunta: limpa, resposta: null }]);
    setPergunta("");
    try {
      const resposta = await api.perguntar(limpa);
      if (ativo.current) setFalas((atual) => atual.map((f) => (f.id === id ? { ...f, resposta } : f)));
    } catch (e) {
      if (!ativo.current) return;
      setFalas((atual) => atual.filter((f) => f.id !== id));
      setPergunta(limpa);
      setErro(`O assistente não respondeu: ${(e as Error).message}`);
    }
  }

  function enviar(evento: FormEvent) {
    evento.preventDefault();
    void perguntar(pergunta);
  }

  return (
    <>
      <p><Link to="/">← Minhas contas</Link></p>
      <h1>Pergunte ao assistente</h1>
      <p className="suave">
        Ele responde sobre o seu saldo, os seus lançamentos e as regras do Pix e da conta. Só lê: não faz Pix nem muda nada.
        As respostas são escritas por um modelo de linguagem que roda no próprio banco, e todo valor em reais é conferido com o seu extrato e a ajuda do banco antes de aparecer.
      </p>

      {falas.length > 0 && (
        <ol className="conversa" aria-label="Conversa" aria-live="polite">
          {falas.map((f) => (
            <li key={f.id}>
              <p className="fala pergunta"><span className="visualmente-oculto">Você: </span>{f.pergunta}</p>
              {f.resposta ? <RespostaNaTela resposta={f.resposta} /> : (
                <p className="fala resposta suave" aria-busy="true">Consultando…</p>
              )}
            </li>
          ))}
        </ol>
      )}

      {erro && <p className="erro" role="alert">{erro}</p>}

      <form className="cartao" onSubmit={enviar}>
        <label htmlFor="pergunta">Sua pergunta</label>
        <textarea
          id="pergunta"
          value={pergunta}
          onChange={(e) => setPergunta(e.target.value)}
          maxLength={TAMANHO_MAXIMO}
          placeholder="Ex.: quanto recebi de Pix este mês?"
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void perguntar(pergunta);
            }
          }}
        />
        <p className="suave">{pergunta.length} de {TAMANHO_MAXIMO} caracteres</p>
        <div className="acoes">
          <button type="submit" disabled={esperando || !pergunta.trim()}>{esperando ? "Consultando…" : "Perguntar"}</button>
        </div>
        {falas.length === 0 && (
          <div className="acoes" role="group" aria-label="Sugestões">
            {SUGESTOES.map((s) => (
              <button key={s} type="button" className="secundario" onClick={() => void perguntar(s)}>{s}</button>
            ))}
          </div>
        )}
      </form>
    </>
  );
}

function RespostaNaTela({ resposta }: { resposta: RespostaDoAssistente }) {
  return (
    <div className={`fala resposta${resposta.origem === "MODELO_DE_LINGUAGEM" ? "" : " aviso"}`} role="article" aria-label="Resposta do assistente">
      {resposta.texto}
      {resposta.fontes.length > 0 && <p className="suave">Com base em: {resposta.fontes.join(" · ")}</p>}
      {resposta.origem === "RESERVA" && (
        <p className="suave">Resposta padrão: o assistente não conseguiu responder com segurança desta vez.</p>
      )}
    </div>
  );
}
