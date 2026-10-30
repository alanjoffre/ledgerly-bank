package io.github.alanjoffre.ledgerly.antifraude.dominio;

/** O que o pix faz com o Pix avaliado. */
public enum Decisao {
    /** Segue: o pix debita o pagador. */
    APROVAR,
    /** Para antes do débito e espera uma pessoa do backoffice liberar ou recusar. */
    SEGURAR,
    /** Não sai: o risco é alto demais até para pedir a uma pessoa. */
    RECUSAR
}
