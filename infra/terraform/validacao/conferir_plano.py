#!/usr/bin/env python3
"""Regras de segurança do projeto, conferidas no plano do Terraform (política como código).

Por que existe: o trivy 0.75 só acusa porta aberta à internet se for SSH ou RDP (regra AWS-0107).
Banco na porta 5432 aberto para 0.0.0.0/0 passa em branco (medido em 30/10/2026, ADR-0040).
Aqui a regra é a do Ledgerly: a internet só entra pelo balanceador, e só na porta 443.

Lê o plano em JSON pela entrada padrão (terraform show -json plano.bin). Sai com erro se achar
alguma violação. O plano é melhor que o código para isso: os módulos e laços já estão expandidos.
"""

import json
import sys

# O único lugar que pode receber da internet: a regra HTTPS do balanceador de carga.
ENTRADA_PUBLICA = "module.seguranca.aws_vpc_security_group_ingress_rule.entrada_https"
MUNDO = {"0.0.0.0/0", "::/0"}


def violacoes(recurso):
    tipo, endereco, depois = recurso["type"], recurso["address"], recurso["change"]["after"] or {}
    origem = {depois.get("cidr_ipv4"), depois.get("cidr_ipv6")} & MUNDO

    if tipo == "aws_vpc_security_group_ingress_rule" and origem:
        if not endereco.startswith(ENTRADA_PUBLICA + "["):
            yield "aberto para a internet: só o balanceador de carga pode"
        elif depois.get("from_port") != 443 or depois.get("to_port") != 443:
            yield "o balanceador só abre a porta 443"

    if tipo == "aws_vpc_security_group_egress_rule" and origem:
        if depois.get("ip_protocol") != "tcp" or depois.get("from_port") != 443 or depois.get("to_port") != 443:
            yield "saída para a internet só em HTTPS (porta 443)"

    if tipo == "aws_security_group" and (depois.get("ingress") or depois.get("egress")):
        yield "regra dentro do grupo de segurança: use os recursos de regra, que esta conferência enxerga"

    if tipo in ("aws_db_instance", "aws_docdb_cluster"):
        if depois.get("publicly_accessible"):
            yield "banco com endereço público"
        if depois.get("master_password") or depois.get("password"):
            yield "senha de banco no código: ela iria para o estado do Terraform"


def main():
    plano = json.load(sys.stdin)
    recursos = [r for r in plano.get("resource_changes", []) if "create" in r["change"]["actions"]]
    achados = [(r["address"], v) for r in recursos for v in violacoes(r)]
    for endereco, motivo in achados:
        print(f"  VIOLAÇÃO  {endereco}\n            {motivo}")
    print(f"{len(recursos)} recursos conferidos, {len(achados)} violação(ões)")
    return 1 if achados else 0


if __name__ == "__main__":
    sys.exit(main())
