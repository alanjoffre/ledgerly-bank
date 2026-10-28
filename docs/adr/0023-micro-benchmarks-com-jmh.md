# ADR-0023: Micro-benchmarks com o JMH

- **Status:** Aceita
- **Data:** 2026-10-28

## Contexto

Algumas decisões do projeto dependem de quanto custa um trecho pequeno de código, e não o sistema
inteiro:

- o dinheiro é `BigDecimal` (ADR-0007). Quanto isso custa perto de guardar centavos num `long`?
- as contas quentes viraram sub-contas, pela mesma ideia do `LongAdder` (ADR-0019). Quanto o
  `LongAdder` ganha de um contador disputado, e por quê?
- *virtual threads* (ADR-0020): em que tipo de trabalho elas ganham, e em qual não?

O teste de carga (ADR-0021) não responde: ele mede o sistema inteiro, onde esses custos se perdem
no meio do banco e da rede. E medir um trecho pequeno com `System.nanoTime()` antes e depois engana:
o JIT (o compilador que otimiza o Java enquanto ele roda) ainda não aqueceu, ou percebe que o
resultado não é usado e **apaga a conta inteira**, e o "benchmark" mede o nada.

## Decisão

Usar o **JMH** (*Java Microbenchmark Harness*, 1.37), a ferramenta do próprio OpenJDK para isso,
num módulo à parte, `desempenho/jmh`.

- **Aquecimento e medição separados** (`@Warmup`, `@Measurement`) e um processo novo por
  benchmark (`@Fork`), para uma medição não herdar o JIT da outra.
- **Todo resultado é devolvido** pelo método `@Benchmark`: o JMH o consome, e o JIT não pode
  apagar a conta.
- **Os dados são preparados fora da medição** (`@Setup`), com semente fixa (os mesmos números em
  toda rodada).
- O build gera `desempenho/jmh/target/benchmarks.jar` (pelo *shade plugin*), que roda sozinho:
  `java -jar desempenho/jmh/target/benchmarks.jar`. Como a carga, fica **fora do `./mvnw verify`**:
  compila no build, roda quando alguém pede.

Três benchmarks, cada um ligado a uma decisão:

| Benchmark | Compara | Decisão que apoia |
| --- | --- | --- |
| `DinheiroBenchmark` | somar e comparar mil valores: `BigDecimal` × `long` de centavos | ADR-0007 |
| `ContadorBenchmark` | 16 threads somando no mesmo contador: `synchronized`, `ReentrantLock`, `AtomicLong`, `LongAdder` | ADR-0019 |
| `ThreadsBenchmark` | 10 mil tarefas, de espera (10 ms parada) e de cálculo (só CPU): *virtual threads* × *pool* de 200 threads de plataforma | ADR-0020 |

## Números

Intel Core i7-13620H (16 threads de hardware), Java 25. A saída completa e o JSON estão em
`desempenho/resultados/jmh/`. O "±" é o intervalo de confiança de 99,9% que o JMH calcula.

### Dinheiro: `BigDecimal` × `long` (ADR-0007)

| Mil valores | `BigDecimal` | `long` de centavos | Diferença |
| --- | --- | --- | --- |
| Somar | 4,21 ± 0,98 µs | 0,29 ± 0,04 µs | 14× |
| Comparar | 0,76 ± 0,04 µs | 0,16 ± 0,01 µs | 4,9× |

O `long` é 14 vezes mais rápido, e **não muda nada no razão**. Uma soma de `BigDecimal` custa uns
4 nanossegundos; um lançamento faz menos de dez contas, uns 40 ns, dentro de um pedido que leva
milissegundos (o banco, a rede, o `COMMIT`). O `BigDecimal` continua: ele carrega a escala e o
arredondamento bancário, e o `long` deixaria isso para cada programador lembrar.

### Contador disputado (ADR-0019)

16 threads somando no mesmo contador, em milhões de somas por segundo (todas as threads juntas):

| Contador | Milhões por segundo | Em relação ao `synchronized` |
| --- | --- | --- |
| `synchronized` | 9,9 ± 0,4 | 1× |
| `ReentrantLock` | 39,6 ± 3,6 | 4× |
| `AtomicLong` | 57,0 ± 7,6 | 6× |
| `LongAdder` | **1.423 ± 260** | **143×** |

O `AtomicLong` não trava, mas todas as threads ainda brigam pela mesma posição de memória: cada
soma que perde a disputa tenta de novo. O `LongAdder` dá uma célula a cada thread e só soma as
células quando alguém lê: **25 vezes o `AtomicLong`**. É a mesma ideia das sub-contas do Caixa, e
o mesmo resultado: o ganho não vem de travar mais rápido, vem de **não disputar**.

### *Virtual threads* × threads de plataforma (ADR-0020)

10 mil tarefas, em milissegundos para terminar todas (menos é melhor):

| Tarefa | *Virtual threads* | *Pool* de 200 (Tomcat) | *Pool* de 16 (tamanho da máquina) |
| --- | --- | --- | --- |
| Espera (10 ms parada) | **29,9 ± 9,9** | 509 ± 2 | 6.348 ± 8 |
| Cálculo (~50 µs de CPU) | 44,0 ± 9,4 | 57,4 ± 15,7 | 45,1 ± 5,8 |

- **Na espera, as *virtual threads* ganham por ordem de grandeza:** 17 vezes o *pool* do Tomcat.
  Os *pools* batem com a conta: 10 mil esperas de 10 ms, 200 de cada vez, dão 500 ms; 16 de cada
  vez, 6.250 ms. As *virtual threads* esperam todas ao mesmo tempo.
- **No cálculo, empatam com o *pool* do tamanho da máquina**, dentro da margem: a CPU é a mesma, e
  não há espera para aproveitar. O *pool* de 200 fica um pouco atrás porque são 200 threads do
  sistema disputando 16 threads de hardware.

E isso explica a ADR-0020: o razão espera uma coisa só, uma das suas 20 conexões com o banco.
Esperar barato não ajuda quando o limite é a quantidade de conexões.

### A armadilha pegou o próprio projeto

A primeira versão do `ThreadsBenchmark` calculava `z = z * a + c` 20 mil vezes. O resultado saiu
impossível: as *virtual threads* terminavam o cálculo em 2,8 ms, menos que o tempo mínimo de CPU que
16 threads de hardware precisariam. Medida sozinha, cada tarefa custava 0,75 µs em vez de 50: 20 mil
voltas em que cada uma depende da anterior não cabem nesse tempo, então o JIT achou um atalho para o
laço (provavelmente juntando voltas, já que multiplicar e somar constantes repetidas vezes pode ser
reescrito com menos contas; o código de máquina não foi inspecionado). O benchmark media a criação
das tarefas, e não a conta.

A correção foi uma conta que o JIT não consegue encurtar (deslocamento, ou-exclusivo e
multiplicação, cada volta dependendo da anterior), calibrada em uns 50 µs. O benchmark corrigido
também passou a criar os *pools* uma vez só, na preparação, como num servidor, e ganhou o *pool* do
tamanho da máquina, sem o qual o "cálculo" parecia uma vitória das *virtual threads*.

A lição fica registrada: **um número que fura a física é um erro do benchmark**, até prova em
contrário.

## Alternativas consideradas

- **`System.nanoTime()` antes e depois, num laço** — o erro clássico: sem aquecimento, sem
  proteção contra o JIT apagar a conta, sem intervalo de confiança.
- **Medir no teste de carga** — mede o sistema inteiro; uma diferença de microssegundos some no
  meio de milissegundos de banco e rede. Os dois respondem perguntas diferentes, e o projeto tem os
  dois.
- **Rodar no CI** — mesma razão da ADR-0021: máquina compartilhada, número oscilando.

## Consequências

- **Positivas:** as decisões de dinheiro, contas quentes e *virtual threads* ficam com um número
  ao lado, reprodutível com um comando.
- **Negativas:**
  - micro-benchmark mede um trecho isolado, num caso montado. Um ganho de 10 vezes num trecho que
    ocupa 1% do pedido muda o pedido em menos de 1%. Por isso cada benchmark aponta a decisão que
    apoia, e nenhum é usado para justificar otimização onde o teste de carga não mostrou gargalo;
  - os números dependem da máquina (a mesma ressalva da ADR-0021): comparam alternativas entre si,
    na mesma rodada.
