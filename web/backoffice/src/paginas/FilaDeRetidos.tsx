import { formatarDataHora, formatarReais } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api, type Transferencia } from "../api.ts";

/** Os Pix que o antifraude segurou antes do débito (RETIDA): nenhum dinheiro saiu ainda (ADR-0033). */
export function FilaDeRetidos() {
  const [fila, setFila] = useState<Transferencia[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    api.retidas().then(setFila, (e: Error) => setErro(e.message));
  }, []);

  return (
    <>
      <h1>Pix retidos</h1>
      <p className="suave">Segurados pelo antifraude antes do débito. Liberar faz o Pix seguir; recusar encerra sem mover dinheiro.</p>
      {erro && <p className="erro" role="alert">{erro}</p>}
      {!erro && !fila && <p aria-busy="true">Carregando a fila…</p>}
      {fila && fila.length === 0 && <p className="ok" role="status">Nenhum Pix retido.</p>}
      {fila && fila.length > 0 && (
        <table aria-label="Pix retidos">
          <thead>
            <tr><th>Desde</th><th>Nota</th><th>Valor</th><th>Pagador</th><th className="esconder-no-celular">Recebedor</th><th /></tr>
          </thead>
          <tbody>
            {fila.map((t) => (
              <tr key={t.id}>
                <td>{formatarDataHora(t.criadaEm)}</td>
                <td className="valor">{t.notaDeRisco ?? <span className="suave" title="O antifraude não respondeu: retido pela regra de valor">sem nota</span>}</td>
                <td className="valor">{formatarReais(t.valor)}</td>
                <td>{t.pagador.nome}</td>
                <td className="esconder-no-celular">{t.recebedor.nome}</td>
                <td><Link className="botao secundario" to={`/retidos/${t.id}`} aria-label={`Analisar o Pix retido de ${t.pagador.nome}`}>Analisar</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
