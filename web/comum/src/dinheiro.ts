/**
 * Dinheiro na tela. A API manda e recebe valores como TEXTO ("1234.50"), nunca como número do
 * JavaScript, que é ponto flutuante e erra centavos (0.1 + 0.2 = 0.30000000000000004). Aqui o
 * valor só é formatado para mostrar — conta nenhuma é feita no navegador (ADR-0012).
 */

const REAIS = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });

/** "1234.5" → "R$ 1.234,50". Formata pelo texto, sem passar por float. */
export function formatarReais(valor: string): string {
  const negativo = valor.startsWith("-");
  const [inteiro = "0", fracao = ""] = valor.replace("-", "").split(".");
  const centavos = (fracao + "00").slice(0, 2);
  // BigInt: inteiro de qualquer tamanho, sem arredondar.
  const partes = REAIS.formatToParts(0);
  const simbolo = partes.find((p) => p.type === "currency")?.value ?? "R$";
  const milhar = BigInt(inteiro).toLocaleString("pt-BR");
  return `${negativo ? "-" : ""}${simbolo} ${milhar},${centavos}`;
}

/**
 * O que a pessoa digitou ("1.234,50", "10", "0,5") → o texto que a API aceita ("1234.50").
 * Devolve null se não for um valor válido (positivo, até 2 casas).
 */
export function lerValorDigitado(digitado: string): string | null {
  const limpo = digitado.trim().replace(/^R\$\s*/, "");
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,2})?$|^\d+(,\d{1,2})?$/.test(limpo)) {
    return null;
  }
  const [inteiro = "0", fracao = ""] = limpo.replaceAll(".", "").split(",");
  const texto = `${BigInt(inteiro).toString()}.${(fracao + "00").slice(0, 2)}`;
  return texto === "0.00" ? null : texto;
}

const DATA_HORA = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: "America/Sao_Paulo" });

export function formatarDataHora(instante: string): string {
  return DATA_HORA.format(new Date(instante));
}
