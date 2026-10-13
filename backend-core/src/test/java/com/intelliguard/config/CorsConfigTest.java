package com.intelliguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Allowed CORS origins come from configuration (env CORS_ALLOWED_ORIGINS), comma-separated.
class CorsConfigTest {

    @Test
    void allowedOriginsAreConfigurable() {
        SecurityConfig config = new SecurityConfig(mock(com.intelliguard.security.JwtAuthenticationFilter.class));
        CorsConfiguration cors = config.corsConfigurationSource(" https://ops.example.org , http://localhost:5173 ,")
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/incidents"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("https://ops.example.org", "http://localhost:5173");
    }
}
