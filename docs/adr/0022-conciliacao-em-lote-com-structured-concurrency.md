# ADR-0022: Conciliação em lote com *Structured Concurrency* e *Scoped Values*

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

**Conciliação** é conferir que dois sistemas contam a mesma história. No Ledgerly Bank, o serviço
de Pix e o razão guardam, cada um no seu banco, os lançamentos de cada Pix (ADR-0015). Um banco de
verdade roda essa conferência todo dia, sobre o dia inteiro: um Pix que existe num lado e não no
outro, ou com valor diferente, é dinheiro que ninguém sabe onde está.

São **milhões de registros** por dia. Três exigências:

1. **Memória constante:** não dá para carregar os dois lados inteiros na memória e comparar.
2. **Usar todos os núcleos da máquina**, para a conferência terminar antes do expediente.
3. **Nada fica rodando solto:** se uma parte falha, a conciliação inteira falha e para. Uma
   conciliação "pela metade" que diz que bateu é pior que nenhuma.

## Decisão

Um módulo próprio, `lotes/conciliacao`, em Java puro (sem Spring), com três peças.

### 1. *Merge join* (junção por intercalação)

Os dois lados chegam **ordenados por id** (é o que o `ORDER BY id` do PostgreSQL entrega, apoiado
no índice da chave primária). Basta andar com um dedo em cada lista, como quem confere duas listas
telefônicas: o menor id dos dois avança; ids iguais são comparados.

| Situação | Divergência |
| --- | --- |
| O id está no Pix e não no razão | `FALTA_NO_RAZAO` |
| O id está no razão e não no Pix | `SOBRA_NO_RAZAO` |
| O id está nos dois, com tipo ou valor diferente | `DIVERGENTE` |

Uma passada só, memória constante: o conciliador guarda só o registro atual de cada lado, o placar
e uma amostra de até 100 divergências para a auditoria olhar.

**Um detalhe que quebraria tudo em silêncio:** o PostgreSQL ordena o UUID como 16 bytes **sem
sinal**; o `UUID.compareTo` do Java compara como números **com sinal**. Um id que começa com `8` a
`f` fica no fim para o banco e no começo para o Java: o *merge join* acharia milhões de faltas e
sobras que não existem. A classe `OrdemDosIds` compara do jeito do banco, e o leitor de cada lado
**confere a ordem enquanto lê**: se uma fonte entregar fora de ordem, a conciliação para com erro,
em vez de inventar divergências.

### 2. *Structured Concurrency* para usar todos os núcleos

O espaço de ids é dividido em **faixas** (`Faixa.dividir(partes)`, pelos 64 bits altos do UUID).
Cada faixa é um *merge join* independente, numa subtarefa do `StructuredTaskScope` (*preview* no
Java 25), cada uma numa *virtual thread*:

```java
try (var escopo = StructuredTaskScope.open()) {
    var tarefas = faixas.stream().map(f -> escopo.fork(() -> conciliar(f))).toList();
    escopo.join();
    return tarefas.stream().map(Subtask::get).reduce(Resultado.VAZIO, Resultado::somar);
}
```

"Estruturada" quer dizer que as subtarefas vivem dentro de um bloco, como variáveis locais:

- o bloco só termina quando **todas** terminam;
- se uma falha, as outras são **canceladas** e o erro sobe (`FailedException`, com a causa dentro);
- nenhuma thread continua rodando depois que a conciliação respondeu.

Cada faixa tem o seu próprio placar, então as subtarefas não disputam nada (sem trava, sem
contador atômico). Os placares só se somam no fim, **na ordem das faixas**: o resultado em paralelo
é idêntico ao em sequência, inclusive a amostra.

O cancelamento é cooperativo: o laço do *merge join* confere a cada 4.096 registros se a thread foi
interrompida. Ler o relógio a cada registro custaria caro; a cada 4.096, o custo some e o
cancelamento ainda chega em microssegundos.

### 3. *Scoped Value* para o contexto da execução

Cada divergência leva o **id da execução**, para a auditoria saber qual rodada a achou. Em vez de
passar esse id por parâmetro em cada método, quem chama o liga uma vez:

```java
ScopedValue.where(Conciliador.EXECUCAO, "conciliacao-2026-10-28").call(() -> conciliador.conciliar(16));
```

As subtarefas do escopo **herdam** o valor. Diferente do `ThreadLocal`: não pode ser alterado no
meio do caminho, some sozinho no fim do bloco e não vaza para a próxima tarefa que reusar a thread.

## Números

Fontes sintéticas (`FonteSintetica`), com 10 faltas, 5 sobras e 20 divergências plantadas por
milhão de registros. `lotes/conciliacao/medir.sh`, cada modo em 3 rodadas, valendo a mais rápida
(as primeiras pagam o aquecimento da JVM). Um notebook com um Intel Core i7-13620H (10 núcleos
físicos, 16 threads de hardware), com os outros serviços do projeto no ar.

| Registros por lado | Faixas | Em sequência | Em paralelo | Ganho |
| --- | --- | --- | --- | --- |
| 10 milhões | 16 | 501 ms | **129 ms** | 3,9× |
| 100 milhões | 16 | 4.243 ms | **1.416 ms** | 3,0× |
| 100 milhões | 64 | 5.342 ms | **1.364 ms** | 3,9× |

- **Cem milhões de registros por lado em menos de 1,5 s**, uns 70 milhões de registros por
  segundo, com o resultado em paralelo **idêntico** ao em sequência nas três medições.
- As divergências plantadas foram todas achadas (com 100 milhões: 987 faltas, 500 sobras, 1.979
  divergentes), e a conta fecha: conciliados + faltas + divergentes = registros do Pix.
- **Memória constante, medida:** com o registro do coletor de lixo ligado (`-Xlog:gc`), cada coleta
  voltou de 657 MB para 2 MB. O *merge join* não acumula nada. As 369 coletas somaram 580 ms em 21 s
  de execução, menos de 3%: o coletor não é o que limita.

**Por que 3× a 4×, e não 16×:** o paralelo para em uns 70 milhões de registros por segundo com 16 ou
com 64 faixas, então não falta trabalho para dividir. O limite provável é o processador: 16 threads
de hardware não são 16 núcleos (são 6 núcleos de desempenho com 2 threads cada, e 4 de eficiência),
e o turbo de um núcleo sozinho cai quando todos trabalham. Não foi medido núcleo a núcleo, então
fica como hipótese. Numa máquina de servidor, com núcleos iguais e sem turbo, o ganho tende a ser
maior. O tempo em sequência variou uns 25% entre execuções (4,2 s e 5,3 s): a mesma ressalva de
ruído da ADR-0021.

## Alternativas consideradas

- **Carregar os dois lados num `HashMap` e comparar** — simples, mas a memória cresce com o
  volume: com 10 milhões de registros por lado, são gigabytes. O *merge join* usa a mesma memória
  para mil ou para um bilhão.
- **Um `JOIN` no SQL** (`FULL OUTER JOIN` entre as duas tabelas) — o Pix e o razão têm bancos
  separados (ADR-0004), de propósito. Juntar os dois num banco só para conciliar desfaz a separação.
- **`ExecutorService` com `Future`** — faz o paralelo, mas se uma faixa falha as outras continuam
  rodando até o fim (ou para sempre), e esquecer um `cancel` ou um `shutdown` deixa thread solta.
  O `StructuredTaskScope` faz isso por construção.
- **`parallelStream()`** — usa o *pool* comum do Java, compartilhado com o resto da aplicação, e
  não tem cancelamento quando uma parte falha.
- **Spring Batch** — o padrão do mercado para lotes com reinício a partir do ponto onde parou. É a
  evolução natural quando a conciliação rodar contra os bancos de verdade; aqui, o objetivo é mostrar
  o algoritmo e a concorrência sem um *framework* no meio.

## Consequências

- **Positivas:** memória constante; usa todos os núcleos; falha de uma faixa derruba a
  conciliação inteira, sem resultado pela metade; resultado em paralelo idêntico ao em sequência
  (o teste confere).
- **Negativas:**
  - `StructuredTaskScope` é *preview* no Java 25: o módulo compila e roda com `--enable-preview`, e
    a API pode mudar na próxima versão do Java (já mudou entre o 21 e o 25). Por isso fica num
    módulo à parte, e não dentro dos serviços;
  - as fontes de hoje são sintéticas (`FonteSintetica`, gerada na hora, sem disco). A leitura dos
    bancos de verdade (`ORDER BY id` com cursor, uma conexão por faixa) é o próximo passo, e a
    interface `Fonte` já tem o formato para isso: `ler(Faixa)` devolve um `Stream` ordenado.
