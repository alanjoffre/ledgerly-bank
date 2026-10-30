# ADR-0041: Circuit breaker do Pix para o antifraude

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

A ADR-0033 decidiu **o que** fazer quando o antifraude não responde: até R$ 500,00 o Pix segue, acima
disso fica retido. Faltava o **custo** de descobrir que ele não responde. Com o antifraude travado
(a conexão entra e a resposta não vem), cada Pix espera o limite de 800 ms antes de chegar à mesma
decisão. O dinheiro está certo, mas o cliente espera, e o serviço atende muito menos.

## Decisão

**Um circuit breaker na chamada ao antifraude**, com a extensão do Quarkus para MicroProfile Fault
Tolerance (`quarkus-smallrye-fault-tolerance`):

- **abre** quando metade das últimas 20 chamadas falhou;
- **aberto**, nenhuma chamada sai por 10 s: o Pix vai direto para a falha segura, com o erro
  `circuito aberto`;
- depois disso entram **chamadas de teste**: 3 certas fecham o circuito, 1 errada o abre de novo.

**O que conta como falha:** tempo esgotado, conexão recusada e 5xx, ou seja, o outro lado não está
atendendo. Um 4xx ou um corpo ilegível continuam sendo "sem resposta" para o Pix, mas não contam:
são respondidos na hora, e abrir o circuito não pouparia espera nenhuma.

**A regra de dinheiro não muda.** O circuito só encurta o caminho até a falha segura da ADR-0033; a
porta `Antifraude` continua sem lançar exceção. Um teste confere: com o circuito aberto, R$ 500,00
conclui, R$ 500,01 fica retido, e o antifraude não recebe nenhum dos dois pedidos.

**Sem *retry*.** O cliente está esperando, o limite é curto, e repetir uma chamada a um serviço
travado só somaria espera e carga. No razão é diferente: lá a Saga repete mais tarde, com a mesma
chave de idempotência (ADR-0015).

## Medição

`desempenho/circuito/medir.sh`: 16 clientes fazendo Pix de R$ 1,00 sem parar, com o razão, o contas,
o antifraude e o Pix de verdade. O antifraude é congelado com `SIGSTOP` por 40 s e volta com
`SIGCONT`. A prova reversa é o mesmo pacote, com o circuito desligado por variável
(`MP_Fault_Tolerance_NonFallback_Enabled=false`). O `medir.sh` religa o pix a cada rodada; razao, contas e antifraude ficam
aquecidos pelas rodadas anteriores, porque a primeira depois de ligar sai bem mais lenta. Duas rodadas alternadas de cada, em 30/10/2026 (última de cada:
[com](../../desempenho/resultados/circuito/com-circuito.txt),
[sem](../../desempenho/resultados/circuito/sem-circuito.txt)):

| Com o antifraude travado | Sem circuito | Com circuito |
| --- | --- | --- |
| Pix por segundo | 18,0 e 18,9 | 121,4 e 130,1 |
| p50 | 831 e 833 ms | 101 e 110 ms |
| p99 | 899 e 2.582 ms | 296 e 507 ms |
| Erros | 0 | 0 |
| Pix sem nota depois de o antifraude voltar | 8 e 16 | 413 e 559 |
| Tempo até a primeira nota depois da volta | 0,2 e 0,3 s | 3,5 e 4,4 s |

O preço está nas duas últimas linhas: o circuito demora a perceber a volta, e nesse intervalo o Pix
continua na falha segura. Esses segundos dependem de quando o antifraude volta: o limite é a espera de 10 s
mais as chamadas de teste. Foi o que levou a uma espera de 10 s, e não de 60 s.

**A fase normal não serve de régua para esta tabela.** Os clientes da medição repetem o Pix, e a
regra de rajada do antifraude (ADR-0032) recusa quase todos, sem chamar o razão: na fase normal, os
Pix saem em ~40 ms e não movem dinheiro. Com o antifraude travado, todos seguem pela falha segura e
fazem débito e crédito. É por isso que o p50 com o circuito aberto (cerca de 105 ms) é maior que o da fase
normal, mesmo sem chamar o antifraude. Entre as duas colunas da tabela, o caminho é o mesmo.

## Consequências

- O circuito é **por cópia** do Pix: cada uma descobre sozinha que o antifraude caiu. Com 5 cópias,
  o antifraude recebe mais chamadas de teste. Dividir o estado exigiria um Redis no caminho de uma
  chamada que existe justamente para não depender de ninguém.
- **Sai mais Pix sem avaliação por segundo.** A espera de 800 ms era um freio acidental: na mesma
  medição, em 40 s de trava, saíram de 4.800 a 5.200 Pix sem nota com o circuito, contra 717 a 750 sem ele.
  Os clientes da medição mandam o próximo assim que o anterior responde, o que exagera a diferença:
  o número real depende de quantos Pix as pessoas pedem. A ADR-0033 aceita o risco até R$ 500,00
  por Pix, mas durante a queda nenhuma regra vale, nem a de rajada. **Um limite de Pix sem avaliação
  por pagador é a defesa que falta, e não foi feito.** Por isso, o circuito aberto precisa de alerta.
- O circuito aberto aparece no log de cada Pix (`circuito aberto`) e nas métricas
  (`ft_circuitbreaker_state_current`, no painel da ADR-0042).
- **Um token recusado não abre o circuito.** Um 401 é um 4xx. Falha do Keycloak ao emitir o token
  também não foi medida: nesse caso, vale o tratamento que já existia ("sem resposta").
- O Pix chama também o razão e o contas sem circuito. No razão, a Saga já trata a falta de resposta
  repetindo mais tarde; no contas, não houve medição que justificasse.

## Alternativas consideradas

- **Só o limite de 800 ms:** é o que havia, e a medição mostra o custo.
- **Limite menor:** 200 ms perderia avaliações boas em qualquer pico do antifraude, com Pix maior
  retido à toa, e ainda seria uma espera por Pix.
- **Resilience4j:** bom, mas é mais uma biblioteca. A extensão do Quarkus já integra com o CDI, com a
  imagem nativa e com as métricas.
