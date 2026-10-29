import { describe, expect, it } from "vitest";
import { formatarDataHora, formatarReais, lerValorDigitado } from "./dinheiro.ts";

describe("formatarReais", () => {
  it.each([
    ["1234.5", "R$ 1.234,50"],
    ["0.10", "R$ 0,10"],
    ["10", "R$ 10,00"],
    ["-7.25", "-R$ 7,25"],
    // Maior que Number.MAX_SAFE_INTEGER: o float erraria; pelo texto, não.
    ["90071992547409930.99", "R$ 90.071.992.547.409.930,99"],
  ])("%s → %s", (valor, esperado) => {
    expect(formatarReais(valor)).toBe(esperado);
  });
});

describe("lerValorDigitado", () => {
  it.each([
    ["10", "10.00"],
    ["10,5", "10.50"],
    ["1.234,56", "1234.56"],
    ["R$ 0,01", "0.01"],
    ["007", "7.00"],
  ])("%s → %s", (digitado, esperado) => {
    expect(lerValorDigitado(digitado)).toBe(esperado);
  });

  it.each(["", "0", "0,00", "-5", "1,234", "abc", "10.5", "1.23,45"])("recusa %j", (digitado) => {
    expect(lerValorDigitado(digitado)).toBeNull();
  });
});

describe("formatarDataHora", () => {
  it("mostra no fuso de São Paulo", () => {
    expect(formatarDataHora("2026-10-28T23:30:00Z")).toBe("28/10/2026, 20:30");
  });
});
