#!/usr/bin/env python3
"""Clientes fazendo Pix sem parar: antifraude normal, travado e de volta. Ver medir.sh."""
import json
import os
import signal
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

CONTAS = "http://localhost:8081"
RAZAO = "http://localhost:8082"
PIX = "http://localhost:8083"
KEYCLOAK = "http://localhost:18081"
CLIENTES = 16
NORMAL_S, TRAVADO_S, VOLTA_S = 20, 40, 30

rotulo, antifraude_pid, arquivo_do_token = sys.argv[1], int(sys.argv[2]), sys.argv[3]
token = open(arquivo_do_token).read().strip()


def api(metodo, url, corpo=None, chave=None, limite=30):
    pedido = urllib.request.Request(url, method=metodo, data=json.dumps(corpo).encode() if corpo else None)
    pedido.add_header("Authorization", "Bearer " + token)
    pedido.add_header("Content-Type", "application/json")
    if chave:
        pedido.add_header("Idempotency-Key", chave)
    try:
        with urllib.request.urlopen(pedido, timeout=limite) as resposta:
            return resposta.status, json.load(resposta)
    except urllib.error.HTTPError as e:
        return e.code, {}


def cliente_semeado(usuario):
    url = f"{KEYCLOAK}/admin/realms/ledgerly/users?username={usuario}&exact=true&briefRepresentation=false"
    status, usuarios = api("GET", url)
    if status != 200 or not usuarios or "cliente_id" not in usuarios[0].get("attributes", {}):
        raise SystemExit("rode antes ./scripts/semear.sh")
    return usuarios[0]["attributes"]["cliente_id"][0]


def conta_nova(cliente_id):
    status, conta = api("POST", CONTAS + "/v1/contas", {"clienteId": cliente_id})
    assert status == 201, status
    for _ in range(100):
        if api("GET", RAZAO + "/v1/contas/" + conta["id"])[0] == 200:
            return conta["id"]
        time.sleep(0.1)
    raise SystemExit("o razão não recebeu a conta")


recebedor = conta_nova(cliente_semeado("bruno"))
chave_pix = f"circuito.{recebedor[:8]}@exemplo.test"
assert api("POST", PIX + "/v1/chaves", {"contaId": recebedor, "tipo": "EMAIL", "valor": chave_pix})[0] == 201

ana = cliente_semeado("ana")
pagadores = []
for _ in range(CLIENTES):
    conta = conta_nova(ana)
    assert api("POST", RAZAO + "/v1/depositos", {"contaId": conta, "valor": "100000.00", "descricao": "carga"},
               f"deposito-{conta}")[0] == 201
    pagadores.append(conta)

# (instante, ms, status, tem nota, situação). R$ 1,00: sem resposta do antifraude, a falha segura deixa passar.
amostras = []
parar = threading.Event()


def cliente(conta):
    while not parar.is_set():
        inicio = time.monotonic()
        try:
            status, corpo = api("POST", PIX + "/v1/transferencias",
                                {"pagadorContaId": conta, "chave": chave_pix, "valor": "1.00", "descricao": "carga"},
                                str(uuid.uuid4()))
        except (OSError, ValueError):
            status, corpo = 0, {}
        fim = time.monotonic()
        amostras.append((fim, (fim - inicio) * 1000, status, corpo.get("notaDeRisco") is not None,
                         corpo.get("situacao", "?")))


fios = [threading.Thread(target=cliente, args=(c,), daemon=True) for c in pagadores]
t0 = time.monotonic()
for fio in fios:
    fio.start()
time.sleep(NORMAL_S)
t_travou = time.monotonic()
os.kill(antifraude_pid, signal.SIGSTOP)
time.sleep(TRAVADO_S)
t_voltou = time.monotonic()
os.kill(antifraude_pid, signal.SIGCONT)
time.sleep(VOLTA_S)
parar.set()
for fio in fios:
    fio.join(timeout=35)


def fase(nome, de, ate):
    linhas = [a for a in amostras if de <= a[0] < ate]
    tempos = sorted(a[1] for a in linhas)
    if not tempos:
        print(f"{nome}: nenhuma resposta")
        return
    erros = sum(1 for a in linhas if a[2] not in (201, 202, 422))
    com_nota = sum(1 for a in linhas if a[3])
    situacoes = {}
    for a in linhas:
        situacoes[a[4]] = situacoes.get(a[4], 0) + 1
    print(f"{nome}: {len(linhas)} Pix em {ate - de:.0f} s ({len(linhas) / (ate - de):.1f}/s) · "
          f"p50 {statistics.median(tempos):.0f} ms · p99 {tempos[int(len(tempos) * .99)]:.0f} ms · "
          f"máx {tempos[-1]:.0f} ms · com nota {com_nota} · erros {erros} · {dict(sorted(situacoes.items()))}")


print(f"Rótulo: {rotulo} · {CLIENTES} clientes em sequência · {time.strftime('%Y-%m-%d %H:%M')}")
fase("Normal  ", t0 + 5, t_travou)
fase("Travado ", t_travou, t_voltou)
fase("Regime  ", t_travou + 15, t_voltou)
fase("De volta", t_voltou, t_voltou + VOLTA_S)
primeira = min((a[0] for a in amostras if a[0] > t_voltou and a[3]), default=None)
if primeira is None:
    print("Nota de volta: nunca, nesta janela")
else:
    print(f"Nota de volta: {primeira - t_voltou:.1f} s depois de o antifraude voltar")
contagem = {}
for a in amostras:
    contagem[a[2]] = contagem.get(a[2], 0) + 1
print("Respostas por status:", dict(sorted(contagem.items())))
