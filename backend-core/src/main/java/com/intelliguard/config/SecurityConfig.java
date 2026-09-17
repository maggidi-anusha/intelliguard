package com.intelliguard.config;

import com.intelliguard.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Ported from SentinelCore's SecurityConfig as a starting point (JWT filter + RBAC pattern).
// Resource-specific rules are added here per-endpoint as controllers are built, rather than
// relying on the frontend to hide buttons - same principle SentinelCore followed.
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Without this, Spring Security's default entry point returns 403 for a
                // missing/invalid token, indistinguishable from a valid token with the wrong
                // role. This makes "not authenticated" 401 and leaves role-based denial (a
                // valid token, wrong role) as 403 via the existing AccessDeniedException path.
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        // Spring Boot forwards to /error internally whenever a controller sends
                        // a non-2xx status (e.g. our ResponseStatusException(409)); without this,
                        // that forwarded request re-enters the filter chain unauthenticated and
                        // anyRequest().authenticated() below masks the real status with a 403.
                        .requestMatchers("/error").permitAll()

                        // TELEMETRY INGESTION - ADMIN only. These are trusted system-level
                        // writes (the simulator/agents), a meaningfully different capability
                        // from a USER registering their own service, so they're gated tighter.
                        // Listed before the general "/api/services/**" POST rule below since
                        // Spring Security's authorizeHttpRequests matches in declaration order.
                        .requestMatchers(HttpMethod.POST, "/api/services/*/metrics")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/services/*/logs")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/security-events")
                        .hasRole("ADMIN")

                        // SERVICES - READ: VIEWER, USER, ADMIN (also covers GET on the nested
                        // /metrics and /logs paths - same read access as the service itself)
                        .requestMatchers(HttpMethod.GET, "/api/services/**")
                        .hasAnyRole("VIEWER", "USER", "ADMIN")

                        // SECURITY EVENTS - READ: VIEWER, USER, ADMIN
                        .requestMatchers(HttpMethod.GET, "/api/security-events")
                        .hasAnyRole("VIEWER", "USER", "ADMIN")

                        // SERVICES - WRITE: USER, ADMIN only
                        .requestMatchers(HttpMethod.POST, "/api/services/**")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/services/**")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/services/**")
                        .hasAnyRole("USER", "ADMIN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(
                "http://localhost:5173"
        ));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
