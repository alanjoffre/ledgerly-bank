# Desempenho

As medições da Fase 3. Dois módulos e uma pasta de números:

| Pasta | O que mede | Decisão |
| --- | --- | --- |
| [`carga/`](carga/) | o sistema inteiro sob carga, com o **Gatling** | [ADR-0021](../docs/adr/0021-teste-de-carga-com-gatling.md) |
| [`jmh/`](jmh/) | trechos pequenos de código, com o **JMH** | [ADR-0023](../docs/adr/0023-micro-benchmarks-com-jmh.md) |
| [`resultados/`](resultados/) | o resumo de cada medição, que vai para o Git | — |

Os dois compilam no `./mvnw verify`, mas **não rodam nele**: rodam quando alguém pede. Número de
desempenho em máquina compartilhada (o CI) oscila demais para valer alguma coisa.

## Teste de carga (Gatling)

O **Gatling** simula clientes chamando a API ao mesmo tempo e mede quantos pedidos por segundo o
serviço atende e em quanto tempo responde. `ContasQuentesSimulacao` tem três cenários, todos no
razão:

| Cenário | Cada pedido | Passa pela conta quente |
| --- | --- | --- |
| `depositos` | um depósito numa conta de cliente | Caixa |
| `pix-envios` | um envio de Pix | Liquidação Pix |
| `transferencias` | uma transferência entre dois clientes | nenhuma (o grupo de controle) |

```bash
./scripts/servicos.sh subir razao                                 # pré-requisito
desempenho/carga/medir.sh depositos meu-rotulo                    # 64 clientes por 30 s
desempenho/carga/medir.sh transferencias meu-rotulo 128 60        # clientes, segundos
```

- O resumo vai para `resultados/carga/<rótulo>-<cenário>.txt`, com a integridade do razão
  conferida depois da carga (`"integro":true`).
- O **relatório com os gráficos** fica em `carga/target/gatling/<simulação>-<data>/index.html`:
  abra no navegador.
- Os números da Fase 3 estão nas ADRs [0019](../docs/adr/0019-contas-quentes-em-subcontas.md)
  (contas quentes: 99 → 489 depósitos por segundo) e
  [0020](../docs/adr/0020-virtual-threads-medidas-no-razao.md) (*virtual threads* × threads de
  plataforma).

**Uma ressalva vale para tudo:** o gerador de carga, o banco e o serviço rodam na mesma máquina, e a
mesma medição varia uns 20% entre execuções. Comparação só vale na mesma sessão, em rodadas
alternadas.

## Micro-benchmarks (JMH)

O **JMH** (*Java Microbenchmark Harness*) mede trechos de microssegundos sem as armadilhas de um
`System.nanoTime()` em volta: aquece o JIT antes, impede que ele apague a conta e dá o intervalo de
confiança.

| Benchmark | Compara |
| --- | --- |
| `DinheiroBenchmark` | `BigDecimal` × `long` de centavos, somando e comparando mil valores |
| `ContadorBenchmark` | `synchronized`, `ReentrantLock`, `AtomicLong` e `LongAdder`, com 16 threads no mesmo contador |
| `ThreadsBenchmark` | *virtual threads* × *pools* de threads de plataforma, em 10 mil tarefas de espera e de cálculo |

```bash
./mvnw -q -pl desempenho/jmh -am package -DskipTests
java -jar desempenho/jmh/target/benchmarks.jar             # todos, uns 10 minutos
java -jar desempenho/jmh/target/benchmarks.jar Contador    # só os que têm "Contador" no nome
```

A saída completa e o JSON da última rodada estão em `resultados/jmh/`. A leitura dos números, e o
erro que o próprio JMH ajudou a achar num benchmark deste projeto, estão na ADR-0023.
