package com.intelliguard.service;

import com.intelliguard.dto.AuthResponse;
import com.intelliguard.dto.LoginRequest;
import com.intelliguard.dto.RegisterRequest;
import com.intelliguard.dto.UserSummaryResponse;
import com.intelliguard.entity.Role;
import com.intelliguard.entity.User;
import com.intelliguard.repository.RoleRepository;
import com.intelliguard.repository.UserRepository;
import com.intelliguard.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Set<String> VALID_ROLES = Set.of("ADMIN", "USER", "VIEWER");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserSummaryResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        String roleName = request.getRole() == null || request.getRole().isBlank()
                ? "USER"
                : request.getRole().toUpperCase();

        if (!VALID_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be one of " + VALID_ROLES);
        }

        Role role = findOrCreateRole(roleName);

        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .role(role)
                .build();

        userRepository.save(user);

        return UserSummaryResponse.builder()
                .username(user.getUsername())
                .email(user.getEmail())
                .role(roleName)
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        String roleName = user.getRole().getName();
        String accessToken = jwtUtil.generateAccessToken(user.getUsername(), roleName);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .username(user.getUsername())
                .role(stripRolePrefix(roleName))
                .build();
    }

    private Role findOrCreateRole(String roleName) {
        String prefixed = "ROLE_" + roleName;
        return roleRepository.findByName(prefixed)
                .orElseGet(() -> roleRepository.save(Role.builder().name(prefixed).build()));
    }

    private String stripRolePrefix(String roleName) {
        return roleName.startsWith("ROLE_") ? roleName.substring(5) : roleName;
    }
}
