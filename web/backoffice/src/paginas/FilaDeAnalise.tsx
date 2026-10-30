import { formatarDataHora, formatarReais } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api, LIMITE_DA_FILA, type Transferencia } from "../api.ts";

/** Os Pix que a Saga não conseguiu terminar sozinha (EM_ANALISE): a mais antiga primeiro. */
export function FilaDeAnalise() {
  const [fila, setFila] = useState<Transferencia[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    api.emAnalise().then(setFila, (e: Error) => setErro(e.message));
  }, []);

  return (
    <>
      <h1>Fila de análise</h1>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {!erro && !fila && <p aria-busy="true">Carregando a fila…</p>}
      {fila && fila.length === 0 && <p className="ok" role="status">Nenhum Pix esperando análise.</p>}
      {fila && fila.length === LIMITE_DA_FILA && (
        <p className="suave" role="note">Mostrando os {LIMITE_DA_FILA} mais antigos. Pode haver mais: decida estes para ver os próximos.</p>
      )}
      {fila && fila.length > 0 && (
        <table aria-label="Pix em análise">
          <thead>
            <tr><th>Desde</th><th>Valor</th><th>Pagador</th><th className="esconder-no-celular">Recebedor</th><th className="esconder-no-celular">Motivo</th><th /></tr>
          </thead>
          <tbody>
            {fila.map((t) => (
              <tr key={t.id}>
                <td>{formatarDataHora(t.criadaEm)}</td>
                <td className="valor">{formatarReais(t.valor)}</td>
                <td>{t.pagador.nome}</td>
                <td className="esconder-no-celular">{t.recebedor.nome}</td>
                <td className="esconder-no-celular suave">{t.motivo}</td>
                <td><Link className="botao secundario" to={`/transferencias/${t.id}`} aria-label={`Analisar o Pix de ${t.pagador.nome}`}>Analisar</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
