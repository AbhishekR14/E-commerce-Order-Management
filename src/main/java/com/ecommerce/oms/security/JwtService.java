package com.ecommerce.oms.security;

import com.ecommerce.oms.user.Role;
import com.ecommerce.oms.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 JWTs (docs/design/10-security-rbac.md).
 * Claims: {@code sub} = user id, {@code email}, {@code role}, {@code wid} (warehouse id, staff only),
 * {@code iat}, {@code exp}. Time comes from the application {@link Clock}, so tests can issue expired tokens.
 */
@Service
public class JwtService {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_WAREHOUSE = "wid";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        byte[] secret = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = Duration.ofMinutes(properties.ttlMinutes());
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(User user) {
        return issue(new AuthUser(user.getId(), user.getEmail(), user.getRole(), user.getWarehouseId()));
    }

    public String issue(AuthUser user) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (user.warehouseId() != null) {
            builder.claim(CLAIM_WAREHOUSE, user.warehouseId());
        }
        return builder.signWith(key).compact();
    }

    /**
     * Verifies the signature and expiry and rebuilds the principal.
     *
     * @throws JwtException if the token is malformed, tampered with or expired
     */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long warehouseId = claims.get(CLAIM_WAREHOUSE, Long.class);
        return new AuthUser(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                warehouseId);
    }
}
