package io.github.alanjoffre.ledgerly.antifraude.dominio;

/** Os seis sinais que o modelo lê, na ordem do treino ({@code ml/treinar.py}). */
public enum Sinal {
    VALOR,
    FORA_DO_COSTUME,
    PIX_NA_ULTIMA_HORA,
    RECEBEDOR_NOVO,
    MADRUGADA,
    CONTA_RECEBEDORA_NOVA
}
