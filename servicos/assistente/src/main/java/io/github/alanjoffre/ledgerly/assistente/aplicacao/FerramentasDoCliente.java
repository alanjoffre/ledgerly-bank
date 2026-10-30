package io.github.alanjoffre.ledgerly.assistente.aplicacao;

import io.github.alanjoffre.ledgerly.assistente.aplicacao.porta.Bancario;
import io.github.alanjoffre.ledgerly.assistente.dominio.Consulta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Conta;
import io.github.alanjoffre.ledgerly.assistente.dominio.Lancamento;
import io.github.alanjoffre.ledgerly.assistente.dominio.Reais;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * As ferramentas que o modelo de linguagem pode chamar numa pergunta: só leitura, e só as contas de
 * quem perguntou (ADR-0036). Uma instância por pergunta.
 *
 * <p>O modelo escolhe a conta pelo número que a ferramenta {@link #minhasContas()} mostrou; o id
 * fica aqui. Número que não é de uma conta da pessoa não vira chamada ao extrato: a ferramenta
 * responde que não achou. Mesmo que o modelo fosse enganado, o extrato confere o dono pelo token.
 *
 * <p>Os textos vão em português, com o dinheiro em reais ("R$ 1.234,56"), e com os totais já
 * somados: modelo de linguagem pequeno erra soma, e a trava de saída só aceita valor que esteja
 * aqui. Cada resultado fica guardado em {@link #consultas()}.
 */
public final class FerramentasDoCliente {

    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    /** O período mais longo que uma consulta de lançamentos cobre. */
    public static final int DIAS_NO_MAXIMO = 90;
    /** Quantos lançamentos, no máximo, a ferramenta lê do extrato e mostra ao modelo. */
    static final int LANCAMENTOS_LIDOS = 200;
    static final int LANCAMENTOS_MOSTRADOS = 30;

    public static final String FORA_DO_AR = "O extrato não respondeu agora. Diga ao cliente para tentar de novo em instantes.";

    private final Bancario bancario;
    private final Cliente cliente;
    private final LocalDate hoje;
    private final List<Consulta> consultas = Collections.synchronizedList(new ArrayList<>());
    private List<Conta> contas;

    public FerramentasDoCliente(Bancario bancario, Cliente cliente, LocalDate hoje) {
        this.bancario = bancario;
        this.cliente = cliente;
        this.hoje = hoje;
    }

    public LocalDate hoje() {
        return hoje;
    }

    public String minhasContas() {
        return registrar("minhasContas", () -> {
            List<Conta> todas = contas();
            if (todas.isEmpty()) {
                return "O cliente não tem conta.";
            }
            return todas.stream()
                    .map(c -> "- agência %s, conta %s".formatted(c.agencia(), c.numero()))
                    .collect(Collectors.joining("\n", "Contas do cliente:\n", ""));
        });
    }

    public String saldo(String numeroDaConta) {
        return registrar("saldo", () -> conta(numeroDaConta)
                .map(c -> "Saldo da conta %s: %s.".formatted(c.numero(), Reais.formatar(bancario.saldo(cliente, c.id()))))
                .orElseGet(() -> naoAchou(numeroDaConta)));
    }

    /**
     * @param de  primeiro dia, {@code AAAA-MM-DD}; sem ele, 30 dias antes de {@code ate}
     * @param ate último dia, {@code AAAA-MM-DD}; sem ele, hoje
     */
    public String lancamentos(String numeroDaConta, String de, String ate) {
        return registrar("lancamentos", () -> {
            Optional<Conta> conta = conta(numeroDaConta);
            if (conta.isEmpty()) {
                return naoAchou(numeroDaConta);
            }
            LocalDate fim;
            LocalDate inicio;
            try {
                fim = vazio(ate) ? hoje : LocalDate.parse(ate.strip());
                inicio = vazio(de) ? fim.minusDays(30) : LocalDate.parse(de.strip());
            } catch (DateTimeParseException e) {
                return "Data inválida: use AAAA-MM-DD (por exemplo %s).".formatted(hoje);
            }
            if (inicio.isAfter(fim)) {
                return "O primeiro dia (%s) vem depois do último (%s).".formatted(inicio, fim);
            }
            if (ChronoUnit.DAYS.between(inicio, fim) > DIAS_NO_MAXIMO) {
                inicio = fim.minusDays(DIAS_NO_MAXIMO);
            }
            LocalDate primeiro = inicio;
            List<Lancamento> doPeriodo = bancario.lancamentos(cliente, conta.get().id(), LANCAMENTOS_LIDOS).stream()
                    .filter(l -> {
                        LocalDate dia = LocalDate.ofInstant(l.registradoEm(), BRASILIA);
                        return !dia.isBefore(primeiro) && !dia.isAfter(fim);
                    })
                    .toList();
            return descrever(conta.get(), inicio, fim, doPeriodo);
        });
    }

    /** O que as ferramentas devolveram nesta pergunta, na ordem. */
    public List<Consulta> consultas() {
        return List.copyOf(consultas);
    }

    private static String descrever(Conta conta, LocalDate inicio, LocalDate fim, List<Lancamento> lancamentos) {
        String cabecalho = "Lançamentos da conta %s de %s a %s".formatted(conta.numero(), inicio, fim);
        if (lancamentos.isEmpty()) {
            return cabecalho + ": nenhum.";
        }
        BigDecimal entradas = soma(lancamentos, true);
        BigDecimal saidas = soma(lancamentos, false);
        String linhas = lancamentos.stream()
                .limit(LANCAMENTOS_MOSTRADOS)
                .map(l -> "- %s · %s · %s · %s · saldo depois: %s".formatted(
                        LocalDate.ofInstant(l.registradoEm(), BRASILIA),
                        l.credito() ? "entrada" : "saída",
                        Reais.formatar(l.valor()),
                        l.descricao(),
                        Reais.formatar(l.saldoApos())))
                .collect(Collectors.joining("\n"));
        String resto = lancamentos.size() > LANCAMENTOS_MOSTRADOS
                ? "\n(e mais %d lançamentos mais antigos, que entram nos totais)".formatted(lancamentos.size() - LANCAMENTOS_MOSTRADOS)
                : "";
        return """
                %s (%d lançamentos, os mais novos primeiro):
                %s%s
                Total de entradas: %s. Total de saídas: %s.""".formatted(
                cabecalho, lancamentos.size(), linhas, resto, Reais.formatar(entradas), Reais.formatar(saidas));
    }

    private static BigDecimal soma(List<Lancamento> lancamentos, boolean credito) {
        return lancamentos.stream()
                .filter(l -> l.credito() == credito)
                .map(Lancamento::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Conta> contas() {
        if (contas == null) {
            contas = List.copyOf(bancario.contas(cliente));
        }
        return contas;
    }

    /** A conta da pessoa com este número (só os dígitos contam); sem número, a única conta dela. */
    private Optional<Conta> conta(String numero) {
        List<Conta> todas = contas();
        if (vazio(numero)) {
            return todas.size() == 1 ? Optional.of(todas.getFirst()) : Optional.empty();
        }
        String digitos = digitos(numero);
        return todas.stream().filter(c -> digitos(c.numero()).equals(digitos)).findFirst();
    }

    private String naoAchou(String numero) {
        String quais = contas().stream().map(Conta::numero).collect(Collectors.joining(", "));
        return vazio(numero)
                ? "Diga o número da conta: o cliente tem estas: %s.".formatted(quais)
                : "Nenhuma conta do cliente tem o número %s. As contas dele: %s.".formatted(numero.strip(), quais);
    }

    private String registrar(String ferramenta, java.util.function.Supplier<String> resultado) {
        String texto;
        try {
            texto = resultado.get();
        } catch (BancarioIndisponivelException e) {
            texto = FORA_DO_AR;
        }
        consultas.add(new Consulta(ferramenta, texto));
        return texto;
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    private static String digitos(String texto) {
        return texto.replaceAll("\\D", "");
    }
}
