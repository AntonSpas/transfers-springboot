package io.moneyflow.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Если заголовок X-Internal-Token совпадает с секретом, помечает запрос как выполненный сервисом
 * (роль INTERNAL_SERVICE). Иначе ничего не делает и пропускает запрос дальше: отказ с кодом 401
 * выдаёт цепочка безопасности, и формат ответа определён в одном месте.
 *
 * <p>Сравниваются SHA-256 от значений, а не сами строки: MessageDigest.isEqual работает за постоянное время,
 * а хеширование выравнивает длины, поэтому по времени ответа нельзя подобрать ни символы, ни длину секрета.
 * Значение заголовка нигде не логируется.
 */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String ROLE = "INTERNAL_SERVICE";

    private final byte[] expectedDigest;

    public InternalApiTokenFilter(String expectedToken) {
        this.expectedDigest = sha256(expectedToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(expectedDigest, sha256(provided))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
