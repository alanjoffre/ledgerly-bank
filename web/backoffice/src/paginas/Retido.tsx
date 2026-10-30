import { ErroDaApi, formatarDataHora, formatarReais } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { api, type Avaliacao, type Explicacao, type Transferencia } from "../api.ts";

const MINIMO = 10;

/** A avaliação que o antifraude não guardou: ele estava fora do ar e o Pix ficou retido pela regra de valor. */
const SEM_AVALIACAO = "sem-avaliacao";

/**
 * A decisão da analista sobre um Pix retido pelo antifraude: ver a nota, os motivos e os fatos que
 * o antifraude viu, e liberar (a Saga segue para o débito) ou recusar (REJEITADA, sem dinheiro
 * movido). O serviço de Pix grava quem decidiu e por quê (ADR-0033).
 */
export function Retido() {
  const { id = "" } = useParams();
  const [pix, setPix] = useState<Transferencia | null>(null);
  const [avaliacao, setAvaliacao] = useState<Avaliacao | typeof SEM_AVALIACAO | null>(null);
  const [justificativa, setJustificativa] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [feito, setFeito] = useState<Transferencia | null>(null);
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    api.transferencia(id).then(setPix, (e: Error) => setErro(e.message));
    api.avaliacao(id).then(setAvaliacao, (e: Error) =>
      e instanceof ErroDaApi && e.status === 404 ? setAvaliacao(SEM_AVALIACAO) : setErro(e.message));
  }, [id]);

  async function decidir(liberar: boolean) {
    setErro(null);
    setOcupado(true);
    try {
      setFeito(await (liberar ? api.liberar : api.recusar)(id, justificativa.trim()));
    } catch (e) {
      setErro(e instanceof ErroDaApi && e.status === 409 ? `Este Pix já foi decidido ou não está mais retido. ${e.message}` : (e as Error).message);
    } finally {
      setOcupado(false);
    }
  }

  const valida = justificativa.trim().length >= MINIMO;

  return (
    <>
      <p><Link to="/retidos">← Pix retidos</Link></p>
      <h1>Analisar Pix retido</h1>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {!pix && !erro && <p aria-busy="true">Carregando…</p>}
      {pix && (
        <section className="cartao" aria-label="O Pix">
          <p className="valor saldo">{formatarReais(pix.valor)}</p>
          <p>De <strong>{pix.pagador.nome}</strong> para <strong>{pix.recebedor.nome}</strong> ({pix.recebedor.chave})</p>
          <p className="suave">Criado em {formatarDataHora(pix.criadaEm)} · <span className="selo">{pix.situacao}</span></p>
          {pix.motivo && <p className="aviso">{pix.motivo}</p>}
          <p><Link to={`/contas/${pix.pagador.contaId}`}>Ver a conta do pagador</Link></p>
        </section>
      )}

      {avaliacao === SEM_AVALIACAO && (
        <p className="aviso">O antifraude não respondeu a tempo: o Pix ficou retido só pela regra de valor, sem nota.</p>
      )}
      {avaliacao && avaliacao !== SEM_AVALIACAO && <PorQue avaliacao={avaliacao} />}

      {feito ? (
        <p className="ok" role="status">
          {feito.situacao === "REJEITADA" ? "Pix recusado. Nenhum dinheiro saiu." : `Pix liberado. Agora está ${feito.situacao}.`}{" "}
          <Link to="/retidos">Voltar à fila</Link>
        </p>
      ) : pix?.situacao === "RETIDA" && (
        <form className="cartao" onSubmit={(e) => e.preventDefault()}>
          <h2>Decidir</h2>
          <label htmlFor="justificativa">Justificativa (mín. {MINIMO} caracteres; fica na auditoria)</label>
          <textarea id="justificativa" value={justificativa} onChange={(e) => setJustificativa(e.target.value)} maxLength={500} />
          <div className="acoes">
            <button type="button" disabled={!valida || ocupado} onClick={() => void decidir(true)}>Liberar</button>
            <button type="button" className="perigo" disabled={!valida || ocupado} onClick={() => void decidir(false)}>Recusar</button>
          </div>
        </form>
      )}
    </>
  );
}

function PorQue({ avaliacao }: { avaliacao: Avaliacao }) {
  const { fatos } = avaliacao;
  return (
    <section className="cartao" aria-label="Por que foi retido">
      <h2>Nota {avaliacao.nota} de 1000 <span className="selo">{avaliacao.decisao}</span></h2>
      <TextoDaExplicacao id={avaliacao.transferenciaId} />
      <ul>
        {avaliacao.motivos.map((m) => <li key={m.codigo}>{m.texto}</li>)}
      </ul>
      <table aria-label="O que o antifraude viu">
        <tbody>
          <tr><th>Valor</th><td className="valor">{formatarReais(fatos.valor)}</td></tr>
          <tr>
            <th>Em relação à média do pagador</th>
            <td>{fatos.razaoDaMedia === null ? "primeiro Pix dele" : `${vezes(fatos.razaoDaMedia)} vezes a média`}</td>
          </tr>
          <tr><th>Pix do pagador na última hora</th><td>{fatos.pixNaUltimaHora}</td></tr>
          <tr><th>Já pagou esta conta antes</th><td>{fatos.recebedorNovo ? "não" : "sim"}</td></tr>
          <tr><th>De madrugada (Brasília)</th><td>{fatos.madrugada ? "sim" : "não"}</td></tr>
          <tr><th>Idade da conta que recebe</th><td>{dias(fatos.idadeDaContaRecebedoraEmDias)}</td></tr>
        </tbody>
      </table>
      <p className="suave">Avaliado em {formatarDataHora(avaliacao.avaliadaEm)} · modelo {avaliacao.modelo}</p>
    </section>
  );
}

/**
 * O texto pode levar alguns segundos na primeira vez (o modelo de linguagem escreve): a nota e os
 * motivos já estão na tela, e o texto entra quando chegar. Se falhar, a tela segue sem ele.
 */
function TextoDaExplicacao({ id }: { id: string }) {
  const [explicacao, setExplicacao] = useState<Explicacao | "falhou" | null>(null);

  useEffect(() => {
    api.explicacao(id).then(setExplicacao, () => setExplicacao("falhou"));
  }, [id]);

  if (explicacao === "falhou") return null;
  if (!explicacao) return <p className="suave" aria-busy="true">Escrevendo a explicação…</p>;
  return (
    <figure aria-label="Explicação">
      <blockquote>{explicacao.texto}</blockquote>
      <figcaption className="suave">
        {explicacao.origem === "MODELO_DE_LINGUAGEM"
          ? `Escrita pelo modelo de linguagem local (${explicacao.modelo}) e conferida número a número com a avaliação.`
          : "Texto fixo, montado com os motivos (o modelo de linguagem não estava disponível ou errou um número)."}
      </figcaption>
    </figure>
  );
}

function vezes(razao: string): string {
  return Number(razao).toLocaleString("pt-BR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

function dias(n: number): string {
  return n === 0 ? "menos de 1 dia" : n === 1 ? "1 dia" : `${n} dias`;
}
