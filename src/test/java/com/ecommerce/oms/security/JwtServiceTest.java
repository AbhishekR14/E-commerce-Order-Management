package com.ecommerce.oms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = "unit-test-secret-0123456789abcdef0123456789";
    static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    static JwtService serviceAt(Instant instant) {
        return new JwtService(new JwtProperties(SECRET, 60), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("issue then parse round-trips every claim")
    void issueAndParse() {
        JwtService service = serviceAt(NOW);
        AuthUser staff = new AuthUser(7L, "staff@oms.test", Role.WAREHOUSE_STAFF, 3L);

        AuthUser parsed = service.parse(service.issue(staff));

        assertThat(parsed).isEqualTo(staff);
    }

    @Test
    void warehouseClaimAbsentForNonStaff() {
        JwtService service = serviceAt(NOW);
        AuthUser customer = new AuthUser(1L, "alice@oms.test", Role.CUSTOMER, null);

        assertThat(service.parse(service.issue(customer)).warehouseId()).isNull();
    }

    @Test
    @DisplayName("a token is rejected once the clock passes exp")
    void expiredTokenRejected() {
        String token = serviceAt(NOW).issue(new AuthUser(1L, "a@b.c", Role.CUSTOMER, null));

        JwtService later = serviceAt(NOW.plus(Duration.ofMinutes(61)));
        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(ExpiredJwtException.class);

        JwtService stillValid = serviceAt(NOW.plus(Duration.ofMinutes(59)));
        assertThat(stillValid.parse(token).id()).isEqualTo(1L);
    }

    @Test
    void tamperedTokenRejected() {
        JwtService service = serviceAt(NOW);
        String token = service.issue(new AuthUser(1L, "a@b.c", Role.CUSTOMER, null));
        // flip a character inside the payload segment
        String[] parts = token.split("\\.");
        char c = parts[1].charAt(5);
        parts[1] = parts[1].substring(0, 5) + (c == 'a' ? 'b' : 'a') + parts[1].substring(6);
        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretRejected() {
        JwtService other = new JwtService(new JwtProperties("another-secret-0123456789abcdef0123456789", 60),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String token = other.issue(new AuthUser(1L, "a@b.c", Role.ADMIN, null));

        assertThatThrownBy(() -> serviceAt(NOW).parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageRejected() {
        assertThatThrownBy(() -> serviceAt(NOW).parse("not.a.jwt")).isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void shortSecretRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", 60), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
