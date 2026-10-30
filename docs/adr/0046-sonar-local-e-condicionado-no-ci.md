# ADR-0046: SonarQube local e Sonar no CI só se houver segredo

- **Status:** Aceita
- **Data:** 2026-10-30

## Contexto

O build já reprova com aviso do compilador e com cobertura baixa. Falta o que a análise estática pega:
código duplicado, regra de segurança, *bug* provável. E um *portfólio* precisa rodar sem depender de conta de
ninguém: quem clona não tem o token, e o [custo é zero](0006-nuvem-com-custo-zero.md).

## Decisão

- **SonarQube Community local**, no perfil `qualidade` do compose (porta 19000, imagem fixada por *digest*).
  Sobe quando se quer analisar, e usa memória só nessa hora.
- **No CI, condicionado:** o job `sonar` do [`qualidade.yml`](../../.github/workflows/qualidade.yml) só roda se
  existir o segredo `SONAR_TOKEN`, no SonarCloud (grátis para projeto público). Sem o segredo, avisa e passa.
- **O portão de qualidade não bloqueia o PR:** é análise semanal, e cada aviso passa por uma pessoa antes de
  virar trabalho.

## Resultado

Medido em 12.235 linhas. Portão de qualidade aprovado; duplicação de 1,2%; 0 *hotspots* de segurança.
Cobertura: ver [a medição final](#cobertura).

Foram 13 *bugs*, 1 vulnerabilidade e 6 *code smells* na primeira análise. **Depois de olhar um a um, nenhum
era defeito real em produção**, e um era de verdade num teste:

| Regra | Onde | Conclusão |
| --- | --- | --- |
| S2699 (teste sem asserção) | `ContratoComRazaoTest` | **Corrigido:** era um defeito meu, em um teste novo |
| S5998 (regex pode estourar a pilha) | `ChavePix`, guardrails | Estoura de fato com ~10 mil caracteres, mas o limite de tamanho (77, 600, 1.200) vem antes. **Agora há teste**, `emailGigante` |
| S2259 (possível nulo) | `AssistenteController`, `Artigos`, `JdbcLancamentoRepository`, `HttpRazao` | Falso positivo: o framework ou a lógica garantem |
| S4502 (CSRF desligado) | serviços | Falso positivo: API sem sessão, com JWT |
| S2583, S8745, S1845 | `Publicadores`, testes, `ColecoesDoMongo` | Sem efeito em produção |

**Falso positivo se documenta aqui, e não se cala no código:** nenhuma anotação `@SuppressWarnings` foi posta
para zerar o painel.

### Cobertura

A primeira análise deu 11,1% de cobertura porque rodou sem o JaCoCo: o número não quer dizer nada. Com o relatório,
a cobertura medida é de **92,6%**.

### As notas do painel

O portão aprovou, mas o painel mostra confiabilidade C e segurança D. Essas notas vêm dos avisos triados acima: a regra
olha o aviso, e não a conclusão da triagem. Como nenhuma anotação cala o aviso, **as notas ficam assim**, e esta seção é
a explicação.

## Alternativas descartadas

- **SonarCloud obrigatório no CI:** todo *fork* ficaria vermelho, sem o segredo.
- **Só as regras do compilador (`-Xlint:all -Werror`):** ficam, mas não veem duplicação nem segurança.
- **Bloquear PR pelo portão:** em projeto de uma pessoa, viraria pressão para silenciar aviso.

## Consequências

- A análise não acontece sozinha enquanto o segredo não existir: é preciso subir o SonarQube, e isso consome
  memória. Até lá, o número da análise é o da última vez que alguém rodou.
- A imagem tem 2,55 GB, e só baixa quem usa o perfil.
