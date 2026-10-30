# Post do LinkedIn — Fase 5 (IA aplicada)

> Imagem: [imagens/fase-5.png](imagens/fase-5.png) (fonte: [imagens/fase-5.html](imagens/fase-5.html);
> as capturas são o backoffice e o internet banking de verdade, tiradas pelo Playwright com
> `web/e2e/capturas.mjs`).
> Antifraude na [ADR-0032](../adr/0032-antifraude-regras-e-modelo-onnx.md); Pix retido e o que o
> cliente vê na [ADR-0033](../adr/0033-pix-retido-antes-do-debito.md); trava de números na
> [ADR-0035](../adr/0035-explicacao-com-trava-de-numeros.md); assistente na
> [ADR-0036](../adr/0036-assistente-rag-ferramentas-e-travas.md); avaliação na
> [ADR-0037](../adr/0037-avaliacao-do-assistente-fora-do-ci.md); o resultado e os dois defeitos que a avaliação
> achou em [avaliacao/assistente/resultados](../../avaliacao/assistente/resultados/2026-10-30-qwen3-4b-instruct-2507-q4-K-M.md).

---

Meu banco aprendeu a desconfiar de um Pix. E o primeiro defeito que achei foi ele contar ao cliente exatamente por que desconfiou. 🕵️

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Esta fase foi IA aplicada, com custo zero: nenhuma API paga, e nenhum dado sai da máquina.

Antes de debitar, o Pix pergunta ao antifraude. A nota vem de regras (rajada de Pix, conta "laranja" aberta ontem) e de uma regressão logística treinada em Python e executada dentro da JVM em ONNX: cerca de 6,5 microssegundos por Pix. Nota alta: o Pix fica retido, sem nenhum dinheiro movido, até a analista liberar ou recusar. Se o antifraude cair, Pix de até R$ 500 segue; acima disso, fica para a analista.

Na revisão, quatro defeitos que os testes não pegavam:

1️⃣ O cliente via a nota e os motivos ("15 vezes a média"). Se o cliente lê, o golpista lê, e aprende a ficar logo abaixo do limite. Agora ele vê "em análise de segurança; nenhum dinheiro saiu".
2️⃣ O artigo de ajuda do assistente explicava o Pix retido citando os limites do antifraude. Mesmo vazamento, por outra porta.
3️⃣ O artigo sobre conta bloqueada dizia que ela não recebe. Recebe; só não envia. O assistente ia responder errado com toda a confiança.
4️⃣ O internet banking não sabia mostrar um Pix retido.

A IA generativa entra em dois lugares, sempre com uma regra conferindo:
🔹 Uma explicação do Pix retido para a analista. Todo número do texto tem de existir na avaliação; um número inventado derruba o texto, e vale um texto fixo.
🔹 Um assistente que responde sobre o extrato: busca na ajuda (RAG com pgvector) e três ferramentas que só leem, com o token do próprio cliente. Pedir um Pix não tem o que chamar.

E como saber se o assistente responde bem? Com uma avaliação: 30 perguntas fixas, conferidas por regra, rodadas contra o modelo de verdade. E ela achou o que os testes não viam. A primeira rodada nem teve número: o modelo "pensava" antes de responder, gastava o limite nisso, e a resposta saía vazia. Troquei pela versão instruct, que responde direto. A segunda deu 4 de 30: o Ollama recusava toda pergunta, porque a ferramenta sem parâmetro ia com o esquema incompleto. Corrigido, com teste, a terceira deu 27 de 30, sem nenhum valor errado ou de outra conta.

Os testes do build não pegavam nenhum dos dois: usam um modelo falso, que aceita qualquer coisa. É a lição da fase passada, de novo: o falso prova o código contra o falso.

1.083 testes automatizados (1.005 no back-end, 71 nas telas e 7 de ponta a ponta), tudo documentado em português.

Próxima fase: Kubernetes, Terraform e observabilidade.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #InteligenciaArtificial #MachineLearning #LLM #BuildInPublic
