import { ENDERECO_DE_LOGIN, quemSouEu, sair, type Eu } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link, Route, Routes } from "react-router";
import { EnviarPix } from "./paginas/EnviarPix.tsx";
import { ExtratoDaConta } from "./paginas/ExtratoDaConta.tsx";
import { MinhasChaves } from "./paginas/MinhasChaves.tsx";
import { MinhasContas } from "./paginas/MinhasContas.tsx";

type Estado = { tipo: "carregando" } | { tipo: "fora" } | { tipo: "falha"; mensagem: string } | { tipo: "dentro"; eu: Eu };

export function App() {
  const [estado, setEstado] = useState<Estado>({ tipo: "carregando" });

  useEffect(() => {
    quemSouEu()
      .then((eu) => setEstado(eu ? { tipo: "dentro", eu } : { tipo: "fora" }))
      .catch((erro: Error) => setEstado({ tipo: "falha", mensagem: erro.message }));
  }, []);

  if (estado.tipo === "carregando") {
    return <main aria-busy="true">Carregando…</main>;
  }
  if (estado.tipo === "falha") {
    return <main><p className="erro" role="alert">Não foi possível falar com o banco: {estado.mensagem}</p></main>;
  }
  if (estado.tipo === "fora") {
    return (
      <main>
        <h1>Ledgerly Bank</h1>
        <p>Internet banking de demonstração. Dados sintéticos; nenhum dinheiro é de verdade.</p>
        <a className="botao" href={ENDERECO_DE_LOGIN}>Entrar</a>
      </main>
    );
  }

  const { eu } = estado;
  return (
    <>
      <header className="topo">
        <Link className="marca" to="/">Ledgerly Bank</Link>
        <span className="quem">
          Olá, {eu.nome} <button className="secundario" onClick={() => void sair()}>Sair</button>
        </span>
      </header>
      <main>
        {!eu.papeis.includes("cliente") ? (
          <p className="aviso" role="alert">Este acesso não é de cliente. O backoffice fica em outro endereço.</p>
        ) : !eu.clienteId ? (
          <p className="aviso" role="alert">Seu acesso ainda não tem uma conta vinculada.</p>
        ) : (
          <Routes>
            <Route path="/" element={<MinhasContas clienteId={eu.clienteId} />} />
            <Route path="/contas/:contaId" element={<ExtratoDaConta />} />
            <Route path="/contas/:contaId/pix" element={<EnviarPix />} />
            <Route path="/contas/:contaId/chaves" element={<MinhasChaves />} />
            <Route path="*" element={<p>Página não encontrada. <Link to="/">Voltar ao início</Link></p>} />
          </Routes>
        )}
      </main>
    </>
  );
}
