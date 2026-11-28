package io.moneyflow.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.notification.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;

/**
 * Пропускает запрос дальше, только если заголовок X-Internal-Token совпадает с секретом; иначе отвечает 401.
 * Ответ одинаков для отсутствующего и неверного токена и для несуществующего пути. На какие пути действует
 * фильтр, определяет его регистрация ({@link InternalApiConfig}); Spring Security в сервисе нет.
 *
 * <p>Сравниваются SHA-256 от значений, а не сами строки: MessageDigest.isEqual работает за постоянное время,
 * а хеширование выравнивает длины, поэтому по времени ответа нельзя подобрать ни символы, ни длину секрета.
 * Значение заголовка нигде не логируется.
 */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expectedDigest;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InternalApiTokenFilter(String expectedToken, ObjectMapper objectMapper, Clock clock) {
        this.expectedDigest = sha256(expectedToken);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expectedDigest, sha256(provided))) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(clock, HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Authentication required"));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
