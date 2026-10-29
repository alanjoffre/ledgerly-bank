package io.github.alanjoffre.ledgerly.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 e 403 no mesmo formato dos outros erros da API: Problem Details (RFC 9457, ADR-0012).
 *
 * <ul>
 *   <li><b>401</b> {@code nao-autenticado}: sem token, token vencido, de outro emissor ou com
 *       assinatura errada. Vai junto o cabeçalho {@code WWW-Authenticate: Bearer} (RFC 6750). O
 *       motivo exato não sai na resposta — fica só no log —, para não ensinar o atacante.</li>
 *   <li><b>403</b> {@code acesso-negado}: token bom, papel errado (cliente na rota de analista).</li>
 * </ul>
 * O recurso de outro cliente não cai aqui: responde <b>404</b>, como se não existisse (ADR-0026).
 */
public final class RespostasDeSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest pedido, HttpServletResponse resposta, AuthenticationException erro)
            throws IOException {
        resposta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ledgerly\"");
        escrever(resposta, HttpStatus.UNAUTHORIZED, "nao-autenticado", "Não autenticado",
                "Envie um token válido no cabeçalho Authorization: Bearer.");
    }

    @Override
    public void handle(HttpServletRequest pedido, HttpServletResponse resposta, AccessDeniedException erro)
            throws IOException {
        escrever(resposta, HttpStatus.FORBIDDEN, "acesso-negado", "Acesso negado",
                "O seu papel não permite esta operação.");
    }

    private static void escrever(HttpServletResponse resposta, HttpStatus status, String codigo, String titulo,
            String detalhe) throws IOException {
        resposta.setStatus(status.value());
        resposta.setContentType("application/problem+json");
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.getWriter().write("""
                {"type":"urn:ledgerly:erro:%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(codigo, titulo, status.value(), detalhe));
    }
}
