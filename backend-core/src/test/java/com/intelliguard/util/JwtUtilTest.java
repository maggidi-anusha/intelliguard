package com.intelliguard.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// With the prod profile, startup fails fast on a missing, short or placeholder JWT secret; dev
// keeps working with no secret (ephemeral key). Secret values never appear in the error.
class JwtUtilTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void prodRejectsMissingShortOrPlaceholderSecrets() {
        for (String bad : new String[]{null, "", "   ", "c2hvcnQtc2VjcmV0", "changeme-changeme-changeme-changeme-0000",
                "please-change-me-before-deploying-1234"}) {
            assertThatThrownBy(() -> new JwtUtil(bad, env("prod")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET")
                    .satisfies(e -> {
                        if (bad != null && !bad.isBlank()) {
                            assertThat(e.getMessage()).doesNotContain(bad);
                        }
                    });
        }
    }

    @Test
    void prodAcceptsAStrongRandomSecret() {
        JwtUtil jwt = new JwtUtil(randomSecret(), env("prod"));
        String token = jwt.generateAccessToken("alice", "ROLE_USER");
        assertThat(jwt.extractUsername(token)).isEqualTo("alice");
    }

    @Test
    void devStillWorksWithoutASecret() {
        JwtUtil jwt = new JwtUtil("", env());
        assertThat(jwt.isTokenValid(jwt.generateAccessToken("alice", "ROLE_VIEWER"))).isTrue();
        assertThat(new JwtUtil(randomSecret(), env("dev")).isTokenValid("not-a-token")).isFalse();
    }
}
