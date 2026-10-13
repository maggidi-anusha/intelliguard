package com.intelliguard.util;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Set;

// Ported from SentinelCore's JwtUtil. Difference: the signing key is now read from
// JWT_SECRET (base64, HS256, >=256 bits) instead of being generated fresh per JVM start,
// otherwise every restart invalidates existing tokens and a multi-instance deployment
// can't verify tokens signed by another instance. Without a secret (dev/local) an ephemeral
// key is used; with the prod profile startup fails instead (requireProductionSecret).
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;

    static final int MIN_PRODUCTION_SECRET_LENGTH = 32;
    private static final Set<String> KNOWN_DEFAULTS = Set.of("secret", "jwt-secret", "jwtsecret", "jwt_secret",
            "your-secret-key", "your_secret_key", "default", "password", "intelliguard", "intelliguard-secret", "test");
    private static final List<String> PLACEHOLDER_MARKERS = List.of("changeme", "change-me", "change_me",
            "replace-me", "replace_me", "replaceme", "your-secret", "example-secret");

    private static final long ACCESS_EXPIRATION = 1000L * 60 * 15; // 15 minutes
    private static final long REFRESH_EXPIRATION = 1000L * 60 * 60 * 24 * 7; // 7 days

    public JwtUtil(@Value("${jwt.secret:}") String configuredSecret, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            requireProductionSecret(configuredSecret);
        }
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("JWT_SECRET not set - generating an ephemeral signing key for this run. " +
                    "Set JWT_SECRET (base64 HS256 key) for any environment where tokens must survive a restart.");
            this.key = Jwts.SIG.HS256.key().build();
        } else {
            this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(configuredSecret));
        }
    }

    // With the prod profile the service must not start on a missing, short or placeholder secret.
    // The value itself is never logged or put in the error message.
    static void requireProductionSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be set when the prod profile is active");
        }
        if (secret.length() < MIN_PRODUCTION_SECRET_LENGTH) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_PRODUCTION_SECRET_LENGTH
                    + " characters when the prod profile is active");
        }
        String lower = secret.toLowerCase();
        if (KNOWN_DEFAULTS.contains(lower) || PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains)) {
            throw new IllegalStateException("JWT_SECRET is a known default/placeholder value; generate a random one "
                    + "(e.g. openssl rand -base64 48)");
        }
    }

    public String generateAccessToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRATION))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_EXPIRATION))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public String extractRole(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
