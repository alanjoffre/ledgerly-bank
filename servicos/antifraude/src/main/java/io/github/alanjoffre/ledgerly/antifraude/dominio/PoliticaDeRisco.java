package io.github.alanjoffre.ledgerly.antifraude.dominio;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Da pontuação do modelo e das regras à nota e à decisão (ADR-0032).
 *
 * <p><b>Os limites</b> saíram da tabela de limiares do treino ({@code ml/metricas.json}), no
 * conjunto de teste:
 * <ul>
 *   <li><b>segurar a partir de 200</b>: retém cerca de 2% dos Pix e pega 56% das fraudes; metade dos
 *       retidos é fraude de verdade;</li>
 *   <li><b>recusar a partir de 900</b>: nenhum alarme falso no teste, mas pega só 10% das fraudes —
 *       o resto vai para uma pessoa.</li>
 * </ul>
 *
 * <p><b>As regras</b> cobrem o que o banco já sabe que é golpe e não quer deixar para a estatística.
 * Elas só sobem a nota até "segurar": regra nenhuma recusa sozinha; quem recusa é uma pessoa, ou o
 * modelo com nota muito alta.
 */
public final class PoliticaDeRisco {

    public static final int NOTA_MAXIMA = 1000;
    public static final int SEGURAR_A_PARTIR_DE = 200;
    public static final int RECUSAR_A_PARTIR_DE = 900;

    /** Rajada: 5 Pix ou mais na hora anterior — o padrão de quem esvazia uma conta tomada. */
    static final int RAJADA = 5;
    /** Conta "laranja": recém-aberta, nunca recebeu deste pagador, e o valor é alto. */
    static final long CONTA_NOVA_DIAS = 2;
    static final BigDecimal VALOR_ALTO = new BigDecimal("1000");
    static final int NOTA_DA_CONTA_LARANJA = 500;

    /** Um sinal do modelo só vira motivo se pesou de verdade (log da razão de chances). */
    static final double PESO_MINIMO_DO_MOTIVO = 0.25;
    static final int MOTIVOS_DO_MODELO = 3;

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public Avaliacao avaliar(PedidoDeAvaliacao pedido, FatosDoPix fatos, Pontuacao pontuacao, Instant agora) {
        int nota = (int) Math.round(pontuacao.probabilidade() * NOTA_MAXIMA);
        List<Motivo> motivos = new ArrayList<>();

        if (fatos.pixNaUltimaHora() >= RAJADA) {
            nota = Math.max(nota, SEGURAR_A_PARTIR_DE);
            motivos.add(new Motivo("regra:rajada",
                    "%d Pix do pagador na última hora".formatted(fatos.pixNaUltimaHora())));
        }
        if (fatos.idadeDaContaRecebedoraEmDias() < CONTA_NOVA_DIAS
                && fatos.recebedorNovo()
                && fatos.valor().compareTo(VALOR_ALTO) >= 0) {
            nota = Math.max(nota, NOTA_DA_CONTA_LARANJA);
            motivos.add(new Motivo("regra:conta-nova-valor-alto",
                    "%s para uma conta aberta há %s, que nunca recebeu deste pagador"
                            .formatted(reais(fatos.valor()), dias(fatos.idadeDaContaRecebedoraEmDias()))));
        }

        pontuacao.pesos().entrySet().stream()
                .filter(e -> e.getValue() >= PESO_MINIMO_DO_MOTIVO)
                .sorted(Map.Entry.<Sinal, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MOTIVOS_DO_MODELO)
                .map(e -> motivoDoModelo(e.getKey(), fatos))
                .filter(m -> motivos.stream().noneMatch(j -> j.texto().equals(m.texto())))
                .forEach(motivos::add);

        Decisao decisao = nota >= RECUSAR_A_PARTIR_DE ? Decisao.RECUSAR
                : nota >= SEGURAR_A_PARTIR_DE ? Decisao.SEGURAR
                : Decisao.APROVAR;
        return new Avaliacao(pedido, fatos, nota, decisao, motivos, pontuacao.modelo(), agora);
    }

    private static Motivo motivoDoModelo(Sinal sinal, FatosDoPix fatos) {
        String texto = switch (sinal) {
            case VALOR -> "valor de " + reais(fatos.valor());
            case FORA_DO_COSTUME -> fatos.razaoDaMedia() == null
                    ? "primeiro Pix do pagador"
                    : "%s vezes a média dos Pix anteriores do pagador".formatted(vezes(fatos.razaoDaMedia()));
            case PIX_NA_ULTIMA_HORA -> "%d Pix do pagador na última hora".formatted(fatos.pixNaUltimaHora());
            case RECEBEDOR_NOVO -> "primeiro Pix do pagador para esta conta";
            case MADRUGADA -> "feito de madrugada (horário de Brasília)";
            case CONTA_RECEBEDORA_NOVA -> "a conta que recebe foi aberta há " + dias(fatos.idadeDaContaRecebedoraEmDias());
        };
        return new Motivo("modelo:" + sinal.name().toLowerCase(Locale.ROOT).replace('_', '-'), texto);
    }

    /** "R$ 1.200,00", como nos motivos (e na explicação). */
    public static String reais(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(valor).replace(' ', ' ');
    }

    /** "15,0": uma casa, vírgula brasileira. */
    public static String vezes(BigDecimal razao) {
        NumberFormat formato = NumberFormat.getNumberInstance(PT_BR);
        formato.setMinimumFractionDigits(1);
        formato.setMaximumFractionDigits(1);
        return formato.format(razao);
    }

    private static String dias(long dias) {
        return dias == 0 ? "menos de 1 dia" : dias == 1 ? "1 dia" : dias + " dias";
    }
}
