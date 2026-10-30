package io.github.alanjoffre.ledgerly.assistente.dominio;

import java.util.List;

/**
 * O que o cliente lê.
 *
 * @param fontes os títulos dos artigos de ajuda que foram junto com a pergunta
 */
public record Resposta(String texto, Origem origem, List<String> fontes) {

    public enum Origem {
        /** Escrita pelo modelo de linguagem e aprovada pela trava de saída. */
        MODELO_DE_LINGUAGEM,
        /** A trava de entrada barrou a pergunta: o modelo nem viu. */
        RECUSA,
        /** O modelo não respondeu, ou a trava de saída barrou a resposta: vale o texto fixo. */
        RESERVA
    }

    public static final String TEXTO_DA_RECUSA =
            "Posso ajudar com o seu saldo, os seus lançamentos e as regras do Pix e da conta. "
                    + "Essa pergunta eu não consigo responder.";

    public static final String TEXTO_DA_RESERVA =
            "Não consegui responder com segurança agora. O seu saldo e os seus lançamentos estão na tela "
                    + "da conta, e as regras do Pix estão na Ajuda.";

    public Resposta {
        fontes = List.copyOf(fontes);
    }

    public static Resposta recusa() {
        return new Resposta(TEXTO_DA_RECUSA, Origem.RECUSA, List.of());
    }

    public static Resposta reserva(List<String> fontes) {
        return new Resposta(TEXTO_DA_RESERVA, Origem.RESERVA, fontes);
    }
}
