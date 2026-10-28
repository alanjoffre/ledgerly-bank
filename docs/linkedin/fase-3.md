# Post do LinkedIn — Fase 3 (Concorrência e desempenho)

> Imagem: [imagens/fase-3.png](imagens/fase-3.png) (fonte: [imagens/fase-3.html](imagens/fase-3.html)).
> Números: contas quentes na [ADR-0019](../adr/0019-contas-quentes-em-subcontas.md); *virtual
> threads* na [ADR-0020](../adr/0020-virtual-threads-medidas-no-razao.md); conciliação na
> [ADR-0022](../adr/0022-conciliacao-em-lote-com-structured-concurrency.md); JMH na
> [ADR-0023](../adr/0023-micro-benchmarks-com-jmh.md).

---

Meu banco fazia 99 depósitos por segundo. O problema não era o banco: era uma linha. 📉

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Esta fase foi sobre desempenho, e a regra foi uma só: nada de "deve ficar mais rápido". Medir antes, medir depois, na mesma máquina e na mesma sessão.

O primeiro teste de carga já mostrou o gargalo. Todo depósito passa pela conta Caixa do banco, e cada lançamento trava a linha dela até o COMMIT. Dois clientes diferentes depositando ao mesmo tempo faziam fila na mesma linha. No mercado isso tem nome: hot account, a conta quente.

A solução foi dividir o Caixa em 16 sub-contas, e cada depósito trava só uma. O saldo do Caixa é a soma delas. É a ideia do LongAdder do Java: em vez de um contador disputado, vários, somados na leitura.

✅ Depósitos: de 99 para 489 por segundo (5×).
✅ Envios de Pix: de 114 para 543 por segundo (4,8×).
✅ O cliente mais azarado (p99) esperava 1 s; agora espera 0,29 s.

No caminho apareceu um buraco que nenhum teste pegava: o razão aceitava receber e também estornar o mesmo Pix, e o dinheiro aparecia do nada. Só a Saga do Pix impedia. Agora cada envio tem no máximo uma saída, conferida na aplicação e garantida por um índice único no banco.

E nem toda promessa se cumpriu. As virtual threads, ligadas no razão desde a primeira fase, perderam para as threads de plataforma: de 14% a 28% menos vazão, em rodadas alternadas. O gargalo era o pool de conexões com o banco: com 64 conexões em vez de 20, a vazão quase dobrou nos dois modos. Esperar barato não ajuda quando o limite é a quantidade de conexões. O razão voltou às threads de plataforma.

O que mais entrou nesta fase:
🔹 Conciliação em lote do Pix com o razão: 100 milhões de lançamentos por lado em 1,4 s, em memória constante, com Structured Concurrency (se uma parte falha, as outras param) e Scoped Values.
🔹 Micro-benchmarks com JMH, que pegaram um erro meu: o JIT tinha encurtado a conta de um benchmark, e o resultado furava a física. Número impossível é erro do benchmark até prova em contrário.
🔹 Cada número com o arquivo da medição no repositório, e as limitações escritas: uma máquina só, com tudo rodando junto.

670 testes automatizados, tudo documentado em português.

Próxima fase: segurança com Keycloak e o front-end em React.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #Desempenho #Concorrencia #ArquiteturaDeSoftware #BuildInPublic
