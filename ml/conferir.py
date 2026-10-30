"""Confere que o treino de agora deu o mesmo modelo que está versionado (ADR-0043).

Uso (da raiz do repositório), com uma cópia dos arquivos versionados feita ANTES de treinar:
    ml/.venv/bin/python ml/conferir.py <pasta com metricas.json, pesos.json e referencia.csv>

A comparação tem tolerância, não é byte a byte: o treino usa float32, e o mesmo código na mesma
biblioteca dá números um pouco diferentes conforme as instruções do processador (AVX2, AVX-512), que
mudam de uma máquina do CI para outra. A folga fica muito acima desse ruído e muito abaixo de qualquer
mudança real no treino.
"""

import csv
import json
import pathlib
import sys

TOLERANCIA = 1e-4
TOLERANCIA_METRICAS = 2e-4  # as métricas são arredondadas em 4 casas: um arredondamento pode virar
# Estas vêm de contagens: um único Pix cruzando o limiar pelo ruído já as muda bem mais que a folga acima
# (retidos: 1 em 15.000 mais o arredondamento; precisão: 1 em 28, no limiar mais alto). Uma mudança real
# no modelo é pega nos pesos e nas probabilidades, que não têm essa folga.
TOLERANCIA_CONTAGENS = {
    "retidos_por_mil": 0.2,
    "precisao": 0.04,
    "revocacao": 0.04,
    "precisao_no_limiar": 0.04,
    "revocacao_no_limiar": 0.04,
}

RAIZ = pathlib.Path(__file__).resolve().parent.parent
METRICAS = RAIZ / "ml/metricas.json"
PESOS = RAIZ / "servicos/antifraude/src/main/resources/modelo/pesos.json"
REFERENCIA = RAIZ / "servicos/antifraude/src/test/resources/modelo/referencia.csv"


def ler_referencia(caminho):
    with open(caminho) as f:
        return [float(v) for linha in csv.DictReader(f) for v in linha.values()]


def ler_pesos(caminho):
    with open(caminho) as f:
        p = json.load(f)
    return p["media"] + p["escala"] + p["pesos"] + [p["intercepto"]]


def numeros(valor):
    """Achata o JSON das métricas numa lista de (caminho, valor), para comparar número a número."""
    if isinstance(valor, dict):
        return [item for k, v in sorted(valor.items()) for item in [(f"{k}.{c}", n) for c, n in numeros(v)]]
    if isinstance(valor, list):
        return [item for i, v in enumerate(valor) for item in [(f"{i}.{c}", n) for c, n in numeros(v)]]
    return [("", valor)]


def maior_diferenca(a, b):
    return max(abs(x - y) for x, y in zip(a, b)) if len(a) == len(b) else float("inf")


def tolerancia_da_metrica(caminho):
    return TOLERANCIA_CONTAGENS.get(caminho.split(".")[-2], TOLERANCIA_METRICAS)


def conferir_metricas(antes, depois):
    a, b = numeros(antes), numeros(depois)
    if [c for c, _ in a] != [c for c, _ in b]:
        return "as chaves mudaram", float("inf")
    if any(not isinstance(x, (int, float)) and x != y for (_, x), (_, y) in zip(a, b)):
        return "um texto mudou", float("inf")
    pares = [(c, x, y) for (c, x), (_, y) in zip(a, b) if isinstance(x, (int, float))]
    exatas = [(x, y) for c, x, y in pares if tolerancia_da_metrica(c) == TOLERANCIA_METRICAS]
    d = maior_diferenca([x for x, _ in exatas], [y for _, y in exatas])
    fora = [c for c, x, y in pares if abs(x - y) > tolerancia_da_metrica(c)]
    return (f"{fora[0]} mudou" if fora else None), d


def main():
    antes = pathlib.Path(sys.argv[1])
    erros = []

    motivo, d = conferir_metricas(json.loads((antes / "metricas.json").read_text()), json.loads(METRICAS.read_text()))
    print(f"metricas.json: maior diferença {d:.2e} (tolerância {TOLERANCIA_METRICAS:.0e}, sem as contagens)")
    if motivo:
        erros.append(f"metricas.json: {motivo}")

    for nome, ler, novo in [("pesos.json", ler_pesos, PESOS), ("referencia.csv", ler_referencia, REFERENCIA)]:
        d = maior_diferenca(ler(antes / nome), ler(novo))
        print(f"{nome}: maior diferença {d:.2e} (tolerância {TOLERANCIA:.0e})")
        if d > TOLERANCIA:
            erros.append(f"{nome} mudou")

    for e in erros:
        print(f"::error::O treino gerou um modelo diferente do versionado: {e}")
    sys.exit(1 if erros else 0)


if __name__ == "__main__":
    main()
