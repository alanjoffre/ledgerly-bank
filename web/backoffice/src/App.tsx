import { ENDERECO_DE_LOGIN, quemSouEu, sair, type Eu } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link, NavLink, Route, Routes } from "react-router";
import { ConsultaDeConta } from "./paginas/ConsultaDeConta.tsx";
import { Decisao } from "./paginas/Decisao.tsx";
import { FilaDeAnalise } from "./paginas/FilaDeAnalise.tsx";

type Estado = { tipo: "carregando" } | { tipo: "fora" } | { tipo: "falha"; mensagem: string } | { tipo: "dentro"; eu: Eu };

export function App() {
  const [estado, setEstado] = useState<Estado>({ tipo: "carregando" });

  useEffect(() => {
    quemSouEu()
      .then((eu) => setEstado(eu ? { tipo: "dentro", eu } : { tipo: "fora" }))
      .catch((erro: Error) => setEstado({ tipo: "falha", mensagem: erro.message }));
  }, []);

  if (estado.tipo === "carregando") return <main aria-busy="true">Carregando…</main>;
  if (estado.tipo === "falha") {
    return <main><p className="erro" role="alert">Não foi possível falar com o banco: {estado.mensagem}</p></main>;
  }
  if (estado.tipo === "fora") {
    return (
      <main>
        <h1>Backoffice de fraude</h1>
        <p>Acesso só da equipe de análise. Dados sintéticos.</p>
        <a className="botao" href={ENDERECO_DE_LOGIN}>Entrar</a>
      </main>
    );
  }

  const { eu } = estado;
  return (
    <>
      <header className="topo">
        <Link className="marca" to="/">Ledgerly · Backoffice</Link>
        <nav className="acoes menu">
          <NavLink to="/">Fila de análise</NavLink>
          <NavLink to="/contas">Contas</NavLink>
        </nav>
        <span className="quem">
          {eu.nome} <button className="secundario" onClick={() => void sair()}>Sair</button>
        </span>
      </header>
      <main>
        {!eu.papeis.includes("analista") ? (
          <p className="aviso" role="alert">Acesso negado: o backoffice é só da equipe de análise.</p>
        ) : (
          <Routes>
            <Route path="/" element={<FilaDeAnalise />} />
            <Route path="/transferencias/:id" element={<Decisao />} />
            <Route path="/contas" element={<ConsultaDeConta />} />
            <Route path="/contas/:contaId" element={<ConsultaDeConta />} />
            <Route path="*" element={<p>Página não encontrada. <Link to="/">Voltar à fila</Link></p>} />
          </Routes>
        )}
      </main>
    </>
  );
}
