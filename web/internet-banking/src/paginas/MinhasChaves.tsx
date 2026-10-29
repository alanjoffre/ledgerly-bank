import { formatarDataHora } from "@ledgerly/comum";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { api, type Chave, type TipoChave } from "../api.ts";

const TIPOS: { tipo: TipoChave; nome: string }[] = [
  { tipo: "EMAIL", nome: "E-mail" },
  { tipo: "TELEFONE", nome: "Celular" },
  { tipo: "CPF", nome: "CPF" },
  { tipo: "ALEATORIA", nome: "Chave aleatória" },
];

export function MinhasChaves() {
  const { contaId = "" } = useParams();
  const [chaves, setChaves] = useState<Chave[] | null>(null);
  const [tipo, setTipo] = useState<TipoChave>("EMAIL");
  const [valor, setValor] = useState("");
  const [mensagem, setMensagem] = useState<{ classe: string; texto: string } | null>(null);
  const [ocupado, setOcupado] = useState(false);

  const recarregar = useCallback(
    () => api.chavesDaConta(contaId).then(setChaves, (e: Error) => setMensagem({ classe: "erro", texto: e.message })),
    [contaId],
  );
  useEffect(() => { void recarregar(); }, [recarregar]);

  async function registrar(evento: FormEvent) {
    evento.preventDefault();
    setOcupado(true);
    setMensagem(null);
    try {
      const nova = await api.registrarChave(contaId, tipo, tipo === "ALEATORIA" ? null : valor.trim());
      setMensagem({ classe: "ok", texto: `Chave ${nova.chave} registrada.` });
      setValor("");
      await recarregar();
    } catch (e) {
      setMensagem({ classe: "erro", texto: (e as Error).message });
    } finally {
      setOcupado(false);
    }
  }

  async function excluir(chave: string) {
    setOcupado(true);
    try {
      await api.excluirChave(chave);
      setMensagem({ classe: "ok", texto: `Chave ${chave} excluída.` });
      await recarregar();
    } catch (e) {
      setMensagem({ classe: "erro", texto: (e as Error).message });
    } finally {
      setOcupado(false);
    }
  }

  return (
    <>
      <p><Link to="/">← Minhas contas</Link></p>
      <h1>Minhas chaves Pix</h1>
      {mensagem && <p className={mensagem.classe} role="status">{mensagem.texto}</p>}
      {!chaves && <p aria-busy="true">Carregando chaves…</p>}
      {chaves && chaves.length === 0 && <p>Nenhuma chave nesta conta.</p>}
      {chaves && chaves.length > 0 && (
        <table>
          <thead><tr><th>Chave</th><th>Tipo</th><th className="esconder-no-celular">Desde</th><th /></tr></thead>
          <tbody>
            {chaves.map((c) => (
              <tr key={c.chave}>
                <td>{c.chave}</td>
                <td>{TIPOS.find((t) => t.tipo === c.tipo)?.nome}</td>
                <td className="esconder-no-celular suave">{formatarDataHora(c.registradaEm)}</td>
                <td><button className="secundario" onClick={() => void excluir(c.chave)} disabled={ocupado} aria-label={`Excluir ${c.chave}`}>Excluir</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>Nova chave</h2>
      <form className="cartao" onSubmit={(e) => void registrar(e)}>
        <label htmlFor="tipo">Tipo</label>
        <select id="tipo" value={tipo} onChange={(e) => setTipo(e.target.value as TipoChave)}>
          {TIPOS.map((t) => <option key={t.tipo} value={t.tipo}>{t.nome}</option>)}
        </select>
        {tipo !== "ALEATORIA" && (
          <>
            <label htmlFor="valor-chave">{TIPOS.find((t) => t.tipo === tipo)?.nome}</label>
            <input id="valor-chave" value={valor} onChange={(e) => setValor(e.target.value)} required />
          </>
        )}
        <div className="acoes"><button type="submit" disabled={ocupado}>Registrar chave</button></div>
      </form>
    </>
  );
}
