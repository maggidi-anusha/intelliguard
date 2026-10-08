package com.intelliguard.service;

import com.intelliguard.dto.RegisterRequest;
import com.intelliguard.dto.UserSummaryResponse;
import com.intelliguard.entity.Role;
import com.intelliguard.entity.User;
import com.intelliguard.repository.RoleRepository;
import com.intelliguard.repository.UserRepository;
import com.intelliguard.service.AuthService.AdminSeedResult;
import com.intelliguard.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Covers the registration role restriction (public registration can never create an ADMIN)
// and the startup admin seeding that replaced the simulator's old ADMIN self-registration.
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest(String role) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("password123");
        request.setEmail("alice@example.com");
        request.setRole(role);
        return request;
    }

    private void stubRoleLookup(String prefixedName) {
        when(roleRepository.findByName(prefixedName))
                .thenReturn(Optional.of(Role.builder().id(1L).name(prefixedName).build()));
    }

    @Test
    void register_withoutRole_defaultsToUser() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        stubRoleLookup("ROLE_USER");

        UserSummaryResponse response = authService.register(registerRequest(null));

        assertThat(response.getRole()).isEqualTo("USER");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_asViewer_isAllowed() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        stubRoleLookup("ROLE_VIEWER");

        UserSummaryResponse response = authService.register(registerRequest("viewer"));

        assertThat(response.getRole()).isEqualTo("VIEWER");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_asAdmin_isForbiddenAndNothingIsSaved() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(registerRequest("ADMIN")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(userRepository, never()).save(any());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void register_withUnknownRole_isBadRequest() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(registerRequest("SUPERUSER")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(userRepository, never()).save(any());
    }

    @Test
    void ensureAdminAccount_createsAdminWhenMissing() {
        when(userRepository.findByUsername("simulator-admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("s3cret-pass")).thenReturn("hashed");
        stubRoleLookup("ROLE_ADMIN");

        AdminSeedResult result = authService.ensureAdminAccount("simulator-admin", "s3cret-pass", null);

        assertThat(result).isEqualTo(AdminSeedResult.CREATED);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole().getName()).isEqualTo("ROLE_ADMIN");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void ensureAdminAccount_leavesExistingAdminUntouched() {
        User existing = User.builder().username("simulator-admin")
                .role(Role.builder().name("ROLE_ADMIN").build()).build();
        when(userRepository.findByUsername("simulator-admin")).thenReturn(Optional.of(existing));

        AdminSeedResult result = authService.ensureAdminAccount("simulator-admin", "s3cret-pass", null);

        assertThat(result).isEqualTo(AdminSeedResult.ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void ensureAdminAccount_neverPromotesAnExistingNonAdmin() {
        User squatter = User.builder().username("simulator-admin")
                .role(Role.builder().name("ROLE_USER").build()).build();
        when(userRepository.findByUsername("simulator-admin")).thenReturn(Optional.of(squatter));

        AdminSeedResult result = authService.ensureAdminAccount("simulator-admin", "s3cret-pass", null);

        assertThat(result).isEqualTo(AdminSeedResult.USERNAME_TAKEN_BY_NON_ADMIN);
        verify(userRepository, never()).save(any());
    }
}
