import { ErroDaApi, formatarDataHora, formatarReais } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { api, type Extrato, type ItemDoExtrato } from "../api.ts";

const OPERACAO: Record<string, string> = {
  DEPOSITO: "Depósito",
  SAQUE: "Saque",
  TRANSFERENCIA: "Transferência",
  PIX_ENVIO: "Pix enviado",
  PIX_RECEBIMENTO: "Pix recebido",
  PIX_ESTORNO: "Pix devolvido",
};

function titulo(item: ItemDoExtrato): string {
  const base = OPERACAO[item.operacao] ?? item.operacao;
  return item.pix?.contraparte ? `${base} · ${item.pix.contraparte}` : base;
}

export function ExtratoDaConta() {
  const { contaId = "" } = useParams();
  const [extrato, setExtrato] = useState<Extrato | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [carregandoMais, setCarregandoMais] = useState(false);

  useEffect(() => {
    api.extrato(contaId).then(setExtrato, (e: unknown) =>
      setErro(e instanceof ErroDaApi && e.status === 404 ? "Ainda não há movimentação nesta conta." : (e as Error).message));
  }, [contaId]);

  async function carregarMais() {
    if (!extrato?.proximaPagina) return;
    setCarregandoMais(true);
    try {
      const pagina = await api.extrato(contaId, extrato.proximaPagina);
      setExtrato({ ...pagina, itens: [...extrato.itens, ...pagina.itens] });
    } catch (e) {
      setErro((e as Error).message);
    } finally {
      setCarregandoMais(false);
    }
  }

  return (
    <>
      <p><Link to="/">← Minhas contas</Link></p>
      <h1>Extrato</h1>
      {erro && <p className="aviso" role="status">{erro}</p>}
      {!erro && !extrato && <p aria-busy="true">Carregando extrato…</p>}
      {extrato && (
        <>
          {extrato.saldo && (
            <p className="cartao">
              Saldo <span className="valor saldo" data-testid="saldo">{formatarReais(extrato.saldo.saldo)}</span>
              <br /><span className="suave">Atualizado em {formatarDataHora(extrato.saldo.atualizadoEm)}</span>
            </p>
          )}
          <table>
            <thead>
              <tr><th>Quando</th><th>O quê</th><th className="esconder-no-celular">Descrição</th><th>Valor</th></tr>
            </thead>
            <tbody>
              {extrato.itens.map((item) => (
                <tr key={item.sequencia}>
                  <td>{formatarDataHora(item.registradoEm)}</td>
                  <td>{titulo(item)}</td>
                  <td className="esconder-no-celular suave">{item.mensagem ?? item.descricao ?? ""}</td>
                  <td className={`valor ${item.tipo === "CREDITO" ? "credito" : "debito"}`}>
                    {item.tipo === "CREDITO" ? "+" : "−"} {formatarReais(item.valor)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {extrato.proximaPagina && (
            <div className="acoes">
              <button className="secundario" onClick={() => void carregarMais()} disabled={carregandoMais}>
                {carregandoMais ? "Carregando…" : "Ver mais antigos"}
              </button>
            </div>
          )}
        </>
      )}
    </>
  );
}
