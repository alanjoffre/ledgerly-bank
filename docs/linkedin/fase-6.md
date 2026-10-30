# Post do LinkedIn — Fase 6 (Infraestrutura e nuvem)

> Imagem: [imagens/fase-6.png](imagens/fase-6.png) (fonte: [imagens/fase-6.html](imagens/fase-6.html);
> as capturas são o Grafana de verdade, durante a medição do circuito, tiradas pelo Playwright).
> Imagens na [ADR-0038](../adr/0038-imagens-multi-stage-e-nativa.md); Kubernetes na
> [ADR-0039](../adr/0039-kubernetes-local-com-k3d-e-helm.md) e as medições em
> [infra/k8s/README.md](../../infra/k8s/README.md); Terraform na
> [ADR-0040](../adr/0040-terraform-validado-e-simulador-local.md); circuito na
> [ADR-0041](../adr/0041-circuit-breaker-no-antifraude.md); telemetria e a medição errada na
> [ADR-0042](../adr/0042-opentelemetry-e-otel-lgtm.md).

---

Congelei o antifraude do meu banco no meio de uma carga de Pix. Sem derrubar, só congelar: o serviço continua "no ar" e não responde. É a pior falha, porque ninguém desiste de esperar. 🧊

Estou construindo um banco digital do zero, em público: o Ledgerly Bank. Esta fase foi operação: fazer o sistema aguentar a falha, e medir se aguentou de verdade.

O resultado da trava, com o antifraude parado por 40 segundos:

🔴 Sem o circuito (só o tempo limite de 800 ms): 18 a 19 Pix por segundo, 830 ms em cada um.
🟢 Com circuit breaker: 121 a 130 Pix por segundo, de 100 a 110 ms.

E tem preço, que também está medido: com o circuito aberto, mais Pix saem sem nota de risco. Foram de 4.800 a 5.200 nos 40 segundos, contra 717 a 750 sem o circuito. A troca é vazão por risco. Durante a queda nenhuma regra do antifraude vale, nem a de rajada, e boa parte desses Pix seria recusada com ele de pé. O que protege o intervalo é a regra que já existia: Pix acima de R$ 500 sem nota fica retido para a analista. Falta um limite por pagador, e isso está anotado como limite conhecido.

O Kubernetes roda na minha máquina, e o que me ensinou não estava em nenhum tutorial:

1️⃣ Com autoscaling, o Postgres ficou sem conexões: 118 erros HTTP 500. O pool padrão de cada cópia somado passava do limite do banco. Com pools menores: 180.000 pedidos, zero erro.
2️⃣ Trocar de versão sob carga sem sondas deu 586 erros, com 3 cópias. Com sondas e 1 cópia, 13. Com um preStop de 5 segundos, zero, com 1 e com 3 cópias.
3️⃣ Matei um pod no meio de 320 Pix. Os 320 concluíram, R$ 3.200,00 certinhos, sem nenhum em dobro. A idempotência pagou a conta: 116 novas tentativas receberam "pedido em andamento" em vez de virar outro Pix.

A AWS inteira está em Terraform: 230 recursos em 12 módulos. O plano roda sem conta na nuvem, e 3 dos 12 módulos (chaves, armazenamento e segredos) são aplicados de verdade num simulador; aplicar todos é um experimento, que não completa. Custaria cerca de US$ 2.750 por mês na AWS. Nunca foi aplicado lá, e a documentação diz isso: cada afirmação é marcada como validada, simulada ou não verificada.

E agora dá para ver: com OpenTelemetry, um Pix aparece num só rastro passando por 4 serviços, com o tempo de cada trecho, e o circuito fica vermelho no painel.

A melhor lição foi a medição que me enganou. A telemetria parecia custar 25% de desempenho. Desconfiei porque não batia com uma rodada anterior. Era a JVM recém-ligada, aquecendo: com o aquecimento certo, o custo ficou dentro do ruído, e escrevi isso na decisão, inclusive o erro. Uma medição que você não questiona é só um número bonito.

1.091 testes automatizados (1.011 no back-end, 73 nas telas e 7 de ponta a ponta, que agora também rodam contra o cluster), tudo documentado em português.

Próxima fase: a última. Testes de mutação, contratos entre serviços e runbooks.

🔗 Código e documentação: github.com/alanjoffre/ledgerly-bank

#Java #Kubernetes #Terraform #OpenTelemetry #BuildInPublic
