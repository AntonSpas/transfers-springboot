package io.moneyflow.account.auth;

import io.moneyflow.account.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** BCrypt учитывает только первые 72 байта пароля. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final long expiresInSeconds;
    /** Хеш-пустышка: при неизвестном email проверяем пароль так же долго, как при известном. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService, JwtProperties jwtProperties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.expiresInSeconds = jwtProperties.accessTokenTtl().toSeconds();
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthResponse register(String email, String password) {
        if (!fitsBcrypt(password)) {
            throw new InvalidPasswordException("Password must be at most " + BCRYPT_MAX_BYTES + " bytes in UTF-8");
        }
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        User user;
        try {
            user = users.saveAndFlush(new User(email, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // гонка: параллельная регистрация того же email проскочила проверку, но не уникальный индекс
            throw new EmailAlreadyRegisteredException();
        }
        return response(user);
    }

    public AuthResponse login(String email, String password) {
        Optional<User> found = users.findByEmail(email);
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        if (found.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return response(found.get());
    }

    private AuthResponse response(User user) {
        IssuedToken token = tokenService.issue(user);
        return new AuthResponse(user.getId(), token.value(), AuthResponse.BEARER, expiresInSeconds);
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
