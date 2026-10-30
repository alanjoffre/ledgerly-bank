"""Treina o modelo de risco do Pix e o exporta em ONNX (ADR-0032).

Uso (da raiz do repositório):
    python3 -m venv ml/.venv && ml/.venv/bin/pip install -r ml/requisitos.txt
    ml/.venv/bin/python ml/treinar.py

Os dados são SINTÉTICOS, gerados aqui com semente fixa: rodar de novo dá o mesmo modelo, byte a
byte. Nenhum dado de pessoa real entra no treino.

O que sai:
- servicos/antifraude/src/main/resources/modelo/risco-pix.onnx  o modelo que a JVM executa
- ml/metricas.json                                             como ele foi no conjunto de teste
- servicos/antifraude/src/test/resources/modelo/referencia.csv  entradas e a probabilidade que o
  Python calculou; o teste de paridade do Java confere que a JVM dá o mesmo número
- servicos/antifraude/src/main/resources/modelo/pesos.json  média, escala e peso de cada sinal (só
  se o escolhido for a regressão logística): com eles, o Java diz quanto cada sinal pesou na nota

Os sinais (a ordem é o contrato com o Java, o enum `Sinal`):
 0 valor_log           log10(1 + valor em reais)
 1 razao_da_media_log  log10 do valor dividido pela média dos Pix anteriores do pagador (0 sem histórico)
 2 pix_na_ultima_hora  quantos Pix o pagador fez na hora anterior
 3 recebedor_novo      1 se o pagador nunca mandou Pix para essa conta
 4 madrugada           1 entre 0h e 5h59 (horário de Brasília)
 5 conta_recebedor_log log10(1 + idade da conta do recebedor, em dias)
"""

import json
import pathlib

import numpy as np
from skl2onnx import to_onnx
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

SEMENTE = 20261029
N = 60_000
TAXA_DE_FRAUDE = 0.02
LIMIAR = 0.5  # a probabilidade a partir da qual o modelo, sozinho, diria "fraude"

RAIZ = pathlib.Path(__file__).resolve().parent.parent
MODELO = RAIZ / "servicos/antifraude/src/main/resources/modelo/risco-pix.onnx"
REFERENCIA = RAIZ / "servicos/antifraude/src/test/resources/modelo/referencia.csv"
METRICAS = RAIZ / "ml/metricas.json"
PESOS = RAIZ / "servicos/antifraude/src/main/resources/modelo/pesos.json"
SINAIS = ["valor_log", "razao_da_media_log", "pix_na_ultima_hora", "recebedor_novo", "madrugada",
          "conta_recebedor_log"]
LIMIARES = [0.1, 0.2, 0.3, 0.5, 0.7, 0.9]


def gerar(rng: np.random.Generator, n: int):
    """Pix legítimos e fraudes (golpe que manda o dinheiro para uma conta "laranja"), com sobreposição
    de propósito: cada sinal de fraude aparece só às vezes (uma fraude pode ter um sinal, ou nenhum),
    e há Pix legítimo com cara de fraude (a compra grande, a conta que um amigo acabou de abrir)."""
    fraude = rng.random(n) < TAXA_DE_FRAUDE

    def sinal(chance_na_fraude, chance_no_normal):
        return np.where(fraude, rng.random(n) < chance_na_fraude, rng.random(n) < chance_no_normal)

    # Cada sinal: na fraude, com a chance indicada; senão, o valor sai da distribuição normal.
    valor_alto = sinal(0.55, 0.05)
    valor = np.where(valor_alto, rng.lognormal(np.log(1500), 0.9, n), rng.lognormal(np.log(120), 1.0, n))

    fora_do_costume = sinal(0.55, 0.06)
    razao = np.where(fora_do_costume, rng.lognormal(np.log(6), 0.8, n), rng.lognormal(0.0, 0.6, n))
    sem_historico = rng.random(n) < 0.08
    razao_log = np.where(sem_historico, 0.0, np.log10(razao))

    rajada = sinal(0.40, 0.03)
    na_hora = np.where(rajada, rng.poisson(3.0, n), rng.poisson(0.4, n))

    recebedor_novo = sinal(0.75, 0.30).astype(float)
    madrugada = sinal(0.30, 0.05).astype(float)

    conta_nova = sinal(0.60, 0.04)  # a conta "laranja" foi aberta há poucos dias
    idade = np.where(conta_nova, rng.exponential(10, n), rng.exponential(900, n))

    x = np.column_stack([
        np.log10(1 + valor),
        razao_log,
        na_hora,
        recebedor_novo,
        madrugada,
        np.log10(1 + idade),
    ]).astype(np.float32)
    return x, fraude.astype(int)


def avaliar(modelo, x, y):
    p = modelo.predict_proba(x)[:, 1]
    previsto = p >= LIMIAR
    return {
        "precisao_media": round(float(average_precision_score(y, p)), 4),
        "auc_roc": round(float(roc_auc_score(y, p)), 4),
        "precisao_no_limiar": round(float(precision_score(y, previsto)), 4),
        "revocacao_no_limiar": round(float(recall_score(y, previsto)), 4),
    }


def main():
    rng = np.random.default_rng(SEMENTE)
    x, y = gerar(rng, N)
    x_treino, x_teste, y_treino, y_teste = train_test_split(
        x, y, test_size=0.25, stratify=y, random_state=SEMENTE)

    candidatos = {
        "regressao_logistica": make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000)),
        "arvores_com_gradiente": GradientBoostingClassifier(
            n_estimators=120, max_depth=3, learning_rate=0.1, random_state=SEMENTE),
    }
    resultados = {}
    for nome, modelo in candidatos.items():
        modelo.fit(x_treino, y_treino)
        resultados[nome] = avaliar(modelo, x_teste, y_teste)

    # Fraude é rara (2%): a métrica que manda é a precisão média (área sob a curva
    # precisão × revocação). A AUC ROC fica alta até para um modelo fraco quando quase tudo é
    # negativo.
    escolhido = max(resultados, key=lambda nome: resultados[nome]["precisao_media"])
    modelo = candidatos[escolhido]

    # Onde cortar: cada limiar troca fraude pega (revocação) por alarme falso (precisão). É a
    # tabela de onde saem os limites de "segurar" e "recusar" do antifraude.
    p_teste = modelo.predict_proba(x_teste)[:, 1]
    limiares = [{
        "limiar": t,
        "retidos_por_mil": round(float((p_teste >= t).mean()) * 1000, 1),
        "precisao": round(float(precision_score(y_teste, p_teste >= t, zero_division=0)), 4),
        "revocacao": round(float(recall_score(y_teste, p_teste >= t)), 4),
    } for t in LIMIARES]

    if escolhido == "regressao_logistica":
        escala, regressao = modelo.steps[0][1], modelo.steps[1][1]
        PESOS.parent.mkdir(parents=True, exist_ok=True)
        PESOS.write_text(json.dumps({
            "sinais": SINAIS,
            "media": [float(v) for v in escala.mean_],
            "escala": [float(v) for v in escala.scale_],
            "pesos": [float(v) for v in regressao.coef_[0]],
            "intercepto": float(regressao.intercept_[0]),
        }, indent=2) + "\n")
    else:
        PESOS.unlink(missing_ok=True)

    onnx = to_onnx(modelo, x_treino[:1], options={id(modelo): {"zipmap": False}},
                   target_opset=17)
    MODELO.parent.mkdir(parents=True, exist_ok=True)
    MODELO.write_bytes(onnx.SerializeToString())

    REFERENCIA.parent.mkdir(parents=True, exist_ok=True)
    amostra = np.concatenate([x_teste[y_teste == 1][:20], x_teste[y_teste == 0][:30]])
    p = modelo.predict_proba(amostra)[:, 1]
    with REFERENCIA.open("w") as saida:
        saida.write("valor_log,razao_da_media_log,pix_na_ultima_hora,recebedor_novo,madrugada,"
                    "conta_recebedor_log,probabilidade\n")
        for linha, prob in zip(amostra, p):
            saida.write(",".join(f"{v:.9g}" for v in linha) + f",{prob:.9g}\n")

    METRICAS.write_text(json.dumps({
        "dados": {"pix": N, "taxa_de_fraude": TAXA_DE_FRAUDE, "teste": len(y_teste), "semente": SEMENTE},
        "limiar": LIMIAR,
        "escolhido": escolhido,
        "candidatos": resultados,
        "limiares_do_escolhido": limiares,
    }, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(resultados, indent=2, ensure_ascii=False))
    print(json.dumps(limiares, indent=2, ensure_ascii=False))
    print("escolhido:", escolhido, "→", MODELO.relative_to(RAIZ), f"({MODELO.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
