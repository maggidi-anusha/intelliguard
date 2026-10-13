package com.intelliguard.controller;

import com.intelliguard.config.SecurityConfig;
import com.intelliguard.entity.Role;
import com.intelliguard.repository.RoleRepository;
import com.intelliguard.repository.UserRepository;
import com.intelliguard.service.AuthService;
import com.intelliguard.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// With the real SecurityConfig and AuthService (repositories mocked): public registration creates
// VIEWERs only; POST /api/admin/users is ADMIN-only, validated, and rejects duplicates.
@WebMvcTest({AuthController.class, AdminUserController.class})
@Import({SecurityConfig.class, AuthService.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private RoleRepository roleRepository;
    @MockitoBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        when(roleRepository.findByName(anyString()))
                .thenAnswer(inv -> Optional.of(Role.builder().id(1L).name(inv.getArgument(0)).build()));
    }

    private static String account(String username, String email, String password, String role) {
        return "{\"username\": \"" + username + "\", \"email\": \"" + email + "\", \"password\": \"" + password + "\""
                + (role == null ? "" : ", \"role\": \"" + role + "\"") + "}";
    }

    @Test
    void publicRegistration_createsViewersOnly() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(account("vera", "vera@example.com", "password123", null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("VIEWER"));
        for (String role : List.of("USER", "ADMIN")) {
            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(account("ursula", "ursula@example.com", "password123", role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(role + " accounts cannot be self-registered; ask an administrator"));
        }
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(account("shorty", "s@example.com", "short", null))).andExpect(status().isBadRequest());
    }

    @Test
    void adminUsers_onlyAdminsMayCreateAccounts() throws Exception {
        String body = account("ulla", "ulla@example.com", "password123", "USER");
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("VIEWER", "USER")) {
            mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(user("u").roles(role))).andExpect(status().isForbidden());
        }
        verify(userRepository, never()).save(any());

        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void adminUsers_validationAndDuplicates() throws Exception {
        for (String bad : List.of(account("ulla", "not-an-email", "password123", "USER"),
                account("ulla", "ulla@example.com", "short", "USER"),
                account("", "ulla@example.com", "password123", "USER"),
                "{\"username\": \"ulla\", \"email\": \"ulla@example.com\", \"password\": \"password123\"}")) {
            mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(bad)
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(account("ulla", "ulla@example.com", "password123", "ROOT"))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());

        when(userRepository.existsByUsername("taken")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(account("taken", "new@example.com", "password123", "USER"))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(account("fresh", "taken@example.com", "password123", "USER"))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isConflict());
        verify(userRepository, never()).save(any());
    }
}
