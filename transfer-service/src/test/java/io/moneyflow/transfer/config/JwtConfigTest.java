package io.moneyflow.transfer.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.moneyflow.transfer.support.PemTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Декодер закреплён за RS256, роли превращаются в ROLE_*, ключ грузится лениво и падает с понятной причиной. */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();
    private final UUID userId = UUID.randomUUID();

    private KeyPair pair;
    private JwtDecoder decoder;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        pair = PemTestSupport.rsa(2048);
        decoder = config.jwtDecoder((RSAPublicKey) pair.getPublic());
    }

    @Test
    void rs256TokenSignedByMatchingKeyIsAccepted() throws Exception {
        String token = rs256((RSAPrivateKey) pair.getPrivate(), Instant.now().plus(Duration.ofMinutes(15)));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        KeyPair foreign = PemTestSupport.rsa(2048);
        String token = rs256((RSAPrivateKey) foreign.getPrivate(), Instant.now().plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void hs256TokenSignedWithPublicKeyBytesIsRejected() throws Exception {
        // классическая атака подмены алгоритма: «секрет» HMAC = публичный ключ
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(Instant.now().plus(Duration.ofMinutes(15))));
        jwt.sign(new MACSigner(pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String token = new PlainJWT(claims(Instant.now().plus(Duration.ofMinutes(15)))).serialize();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = rs256((RSAPrivateKey) pair.getPrivate(), Instant.now().minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rolesBecomeRolePrefixedAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("roles", List.of("USER", "ADMIN"))
                .build();

        var auth = config.jwtAuthenticationConverter().convert(jwt);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo(userId.toString());
        assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void tokenWithoutRolesHasNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(userId.toString())
                .build();

        var auth = config.jwtAuthenticationConverter().convert(jwt);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void keyAndDecoderBeansAreLazyAndFailWithClearCauseWhenFileIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtConfig.class)
                .withPropertyValues("app.jwt.public-key-path=" + dir.resolve("nope.pem"))
                .run(ctx -> {
                    // контекст поднимается: файловые бины ленивые (в тестах их подменяет JwtTestConfig)
                    assertThat(ctx).hasNotFailed();
                    // а при первом обращении, то есть при построении цепочки безопасности, причина понятна
                    assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class))
                            .rootCause()
                            .isInstanceOf(JwtKeyException.class)
                            .hasMessageContaining("не найден");
                });
    }

    @Test
    void decoderIsBuiltFromKeyFile() throws Exception {
        Path file = Files.writeString(dir.resolve("public.pem"), PemTestSupport.publicPem(pair.getPublic()));
        String token = rs256((RSAPrivateKey) pair.getPrivate(), Instant.now().plus(Duration.ofMinutes(15)));

        new ApplicationContextRunner()
                .withUserConfiguration(JwtConfig.class)
                .withPropertyValues("app.jwt.public-key-path=" + file)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(JwtDecoder.class).decode(token).getSubject())
                            .isEqualTo(userId.toString());
                    assertThat(ctx).hasSingleBean(JwtAuthenticationConverter.class);
                });
    }

    private JWTClaimsSet claims(Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("roles", List.of("USER"))
                .issueTime(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expirationTime(Date.from(expiresAt))
                .build();
    }

    private String rs256(RSAPrivateKey key, Instant expiresAt) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims(expiresAt));
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
