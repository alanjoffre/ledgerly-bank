# ADR-0034: Modelo de linguagem local (Ollama), com o LangChain4j puro nos dois serviços

- **Status:** Aceita
- **Data:** 2026-10-29
- **Atualizada:** 2026-10-30, antes do lançamento da v0.5.0, com o que a avaliação achou ([ADR-0037](0037-avaliacao-do-assistente-fora-do-ci.md))

## Contexto

A Fase 5 usa um **modelo de linguagem** (no mercado, *LLM*, *large language model*) em dois lugares:
escrever a explicação de um Pix retido para a analista (ADR-0035) e responder perguntas do cliente
sobre a própria conta (ADR-0036). Três regras do projeto limitam a escolha:

- **custo zero**: nenhuma API paga, nem "só um pouco";
- **nenhum dado sai da máquina**: mesmo sintético, o extrato de um cliente não deve ir para um
  serviço de fora. Num banco de verdade, isso seria uma questão de LGPD e de sigilo bancário;
- **reproduzível**: quem clona o repositório tem de conseguir rodar, com uma placa de vídeo comum.

Também faltava decidir **como** o Java conversa com o modelo. O antifraude é Quarkus e o assistente
é Spring Boot, e cada um tem uma integração "oficial" com o LangChain4j (a biblioteca Java mais
usada para isso).

## Decisão

**O Ollama**, rodando na máquina, em contêiner (`ollama/ollama:0.40.13`, perfil `ia` do compose,
com a placa de vídeo NVIDIA). O Ollama baixa e executa modelos abertos e expõe uma API HTTP local
(`localhost:11434`). Os modelos, todos de cerca de 4 bilhões de parâmetros, cabem numa placa de 6 GB:

| Modelo | Para quê | Por quê |
| --- | --- | --- |
| `gemma3:4b` | a explicação do antifraude | escreve bem em português; a tarefa é só redigir, sem ferramenta |
| `qwen3:4b-instruct-2507-q4_K_M` | o assistente | chama ferramenta (*tool calling*) no Ollama; o `gemma3` não chama. É a versão *instruct* do `qwen3:4b`, que responde direto, sem a etapa de raciocínio (ver abaixo) |
| `bge-m3` | os vetores da busca na ajuda (RAG) | multilíngue, vai bem em português; 1024 dimensões |

**Por que a versão *instruct*** (30/10/2026, na primeira rodada da avaliação, ADR-0037): o `qwen3:4b`
padrão "pensa" antes de responder. O raciocínio gastava o limite de 300 *tokens* e a resposta saía
vazia (medido: resposta `""` depois de 60 s). Desligar o raciocínio pela API (`think: false`) não
bastou: o raciocínio, em inglês, vinha dentro da resposta. A `instruct-2507` é o mesmo modelo treinado
para responder direto: "O saldo da conta é R$ 70." em 12 *tokens*.

`./scripts/modelos.sh` baixa os três. Sem o perfil `ia`, o resto do banco sobe normalmente: o
antifraude usa o texto fixo de reserva e o assistente responde a mensagem de reserva.

**O LangChain4j puro** (1.23.0), sem a extensão do Quarkus e sem o *starter* do Spring. Cada
serviço monta o `OllamaChatModel` em poucas linhas (no adaptador `RedatorOllama` do antifraude, num
`@Bean` do assistente):

- a extensão `quarkus-langchain4j` ainda não tem versão estável para o Quarkus 3.40 do projeto;
- com o mesmo código nos dois serviços, as mesmas versões e o mesmo jeito de testar, quem lê o
  portfólio aprende uma coisa só;
- a configuração fica à vista: temperatura baixa (0,1 no assistente, 0,2 na explicação), tempo
  limite, nenhuma nova tentativa automática (`maxRetries(0)`): quem decide repetir é o serviço.

**Nos testes**, o modelo é um falso roteirizado, sem o Ollama: o build fica rápido, igual em toda
rodada e roda no CI sem placa de vídeo. A qualidade do modelo de verdade se mede à parte (ADR-0037).

## Alternativas consideradas

- **API paga** (OpenAI, Anthropic, Google). Modelos muito melhores, mas quebram o custo zero e
  mandam o extrato para fora.
- **Modelo maior local** (8 bilhões de parâmetros ou mais). Responde melhor, mas não cabe numa placa
  de 6 GB com folga, e fica lento demais para uma conversa.
- **llama.cpp ou vLLM direto.** O Ollama usa o llama.cpp por baixo e resolve download, formato e API.
  O vLLM é para servidor com placa grande.
- **Extensão `quarkus-langchain4j` e *starter* do Spring.** Menos código de configuração, mas uma
  versão a mais para acompanhar em cada serviço, e no Quarkus ainda sem versão estável. Pode entrar
  quando estabilizar: a troca fica num arquivo só por serviço.
- **Spring AI** no assistente. Bom para quem só usa Spring, mas daria dois jeitos diferentes de falar
  com o modelo no mesmo projeto.

## Consequências

- **Positivas:** custo zero, nenhum dado sai da máquina, e quem clona roda tudo com
  `docker compose --profile ia up`.
- **Negativas:** modelo pequeno erra mais: inventa número, erra soma e às vezes ignora instrução. Por
  isso as travas (ADR-0035, ADR-0036) e a avaliação (ADR-0037) não são enfeite. Quem não tem placa
  NVIDIA roda no processador, bem mais devagar.
- **Download grande:** a imagem do Ollama e os três modelos somam vários gigabytes, uma vez só (ficam
  no volume `ollama-modelos`).
