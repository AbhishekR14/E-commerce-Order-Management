package com.ecommerce.oms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.jwt.*} settings. The secret must be at least 32 bytes (HS256); the postgres profile reads it
 * from the {@code JWT_SECRET} environment variable.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long ttlMinutes) {
}
