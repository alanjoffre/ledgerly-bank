{{/*
Funções reaproveitadas pelos modelos (o "_" no nome diz ao Helm que este arquivo não gera YAML).
*/}}

{{/* Rótulos de todo objeto: quem é, de que sistema, quem instalou. Os nomes "app.kubernetes.io/*"
     são os recomendados pelo próprio Kubernetes — ferramentas (kubectl, painéis) entendem. */}}
{{- define "ledgerly.rotulos" -}}
app.kubernetes.io/name: {{ .nome }}
app.kubernetes.io/part-of: ledgerly
app.kubernetes.io/version: {{ .raiz.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .raiz.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .raiz.Chart.Name .raiz.Chart.Version }}
{{- end -}}

{{/* O seletor: o que liga o Service, o HPA e o PDB aos pods de UM serviço. Pequeno e imutável
     (o seletor de um Deployment não pode mudar depois de criado). */}}
{{- define "ledgerly.seletor" -}}
app.kubernetes.io/name: {{ .nome }}
app.kubernetes.io/part-of: ledgerly
{{- end -}}

{{/* As variáveis comuns de cada tipo de serviço (ver o comentário em values.yaml). */}}
{{- define "ledgerly.variaveisComuns" -}}
{{- $kc := .raiz.Values.keycloak -}}
{{- $otel := .raiz.Values.telemetria -}}
{{- if $otel.ligada }}
OTEL_ENDPOINT: {{ $otel.endpoint | quote }}
OTEL_AMOSTRAGEM: {{ $otel.amostragem | quote }}
{{- if eq .svc.tipo "quarkus" }}
OTEL_SDK_DESLIGADO: "false"
{{- else }}
OTEL_LIGADO: "true"
{{- end }}
{{- end }}
{{- if eq .svc.tipo "spring" }}
LEDGERLY_EMISSOR: {{ $kc.emissor | quote }}
LEDGERLY_SEGURANCA_JWKS: {{ printf "%s/protocol/openid-connect/certs" $kc.interno | quote }}
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_TOKENURI: {{ printf "%s/protocol/openid-connect/token" $kc.interno | quote }}
MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
{{- else if eq .svc.tipo "gateway" }}
LEDGERLY_EMISSOR: {{ $kc.emissor | quote }}
MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
{{- else if eq .svc.tipo "quarkus" }}
QUARKUS_OIDC_AUTH_SERVER_URL: {{ $kc.interno | quote }}
QUARKUS_OIDC_TOKEN_ISSUER: {{ $kc.emissor | quote }}
{{- else }}
{{- fail (printf "servicos.%s.tipo deve ser spring, quarkus ou gateway (veio %q)" .nome .svc.tipo) }}
{{- end }}
{{- end -}}
