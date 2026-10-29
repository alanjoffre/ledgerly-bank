import { formatarReais } from "@ledgerly/comum";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api, type Conta } from "../api.ts";

const SITUACAO = { ATIVA: "Ativa", BLOQUEADA: "Bloqueada", ENCERRADA: "Encerrada" } as const;

/** O saldo de cada conta: ainda buscando, o valor, ou indisponível (razão fora do ar). */
type Saldo = "buscando" | "indisponivel" | string;

export function MinhasContas({ clienteId }: { clienteId: string }) {
  const [contas, setContas] = useState<Conta[] | null>(null);
  const [saldos, setSaldos] = useState<Record<string, Saldo>>({});
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    let ativo = true;
    api.contasDoCliente(clienteId).then((lista) => {
      if (!ativo) return;
      setContas(lista);
      // A lista vem sem saldo; cada conta busca o seu, em paralelo, e uma falha não derruba as outras.
      for (const conta of lista) {
        api.conta(conta.id).then(
          (comSaldo) => ativo && setSaldos((atual) => ({ ...atual, [conta.id]: comSaldo.saldo ?? "indisponivel" })),
          () => ativo && setSaldos((atual) => ({ ...atual, [conta.id]: "indisponivel" })),
        );
      }
    }, (e: Error) => ativo && setErro(e.message));
    return () => {
      ativo = false;
    };
  }, [clienteId]);

  if (erro) return <p className="erro" role="alert">{erro}</p>;
  if (!contas) return <p aria-busy="true">Carregando contas…</p>;

  return (
    <>
      <h1>Minhas contas</h1>
      {contas.length === 0 && <p>Você ainda não tem conta aberta.</p>}
      <div className="grade">
        {contas.map((conta) => (
          <section key={conta.id} className="cartao" aria-label={`Conta ${conta.numero}`}>
            <div className="suave">Agência {conta.agencia} · Conta {conta.numero} · <span className="selo">{SITUACAO[conta.situacao]}</span></div>
            <div className="valor saldo" data-testid="saldo">
              <SaldoDaConta saldo={saldos[conta.id] ?? "buscando"} />
            </div>
            <div className="acoes">
              <Link className="botao secundario" to={`/contas/${conta.id}`}>Extrato</Link>
              {conta.situacao === "ATIVA" && <Link className="botao" to={`/contas/${conta.id}/pix`}>Enviar Pix</Link>}
              <Link className="botao secundario" to={`/contas/${conta.id}/chaves`}>Minhas chaves</Link>
            </div>
          </section>
        ))}
      </div>
    </>
  );
}

function SaldoDaConta({ saldo }: { saldo: Saldo }) {
  if (saldo === "buscando") return <span aria-busy="true" className="suave">…</span>;
  if (saldo === "indisponivel") return <>Saldo indisponível agora</>;
  return <>{formatarReais(saldo)}</>;
}
