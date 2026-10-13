package com.intelliguard.service;

import com.intelliguard.dto.AdminCreateUserRequest;
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

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    // Public self-registration only ever creates read-only VIEWER accounts. USER and ADMIN
    // accounts are created by an ADMIN (POST /api/admin/users, createUser below) or, for the
    // simulator's ADMIN, seeded at startup (AdminAccountSeeder) - otherwise anyone could call
    // /api/auth/register with a privileged role and bypass RBAC.
    private static final Set<String> SELF_REGISTRATION_ROLES = Set.of("VIEWER");
    private static final Set<String> PRIVILEGED_ROLES = Set.of("USER", "ADMIN");
    private static final Set<String> ADMIN_CREATABLE_ROLES = Set.of("USER", "VIEWER", "ADMIN");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserSummaryResponse register(RegisterRequest request) {
        String roleName = request.getRole() == null || request.getRole().isBlank()
                ? "VIEWER"
                : request.getRole().toUpperCase();

        if (PRIVILEGED_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    roleName + " accounts cannot be self-registered; ask an administrator");
        }
        if (!SELF_REGISTRATION_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be one of " + SELF_REGISTRATION_ROLES);
        }
        return createAccount(request.getUsername(), request.getEmail(), request.getPassword(), roleName);
    }

    /** ADMIN-only account creation (POST /api/admin/users): USER, VIEWER or ADMIN. */
    public UserSummaryResponse createUser(AdminCreateUserRequest request) {
        String roleName = request.getRole().trim().toUpperCase();
        if (!ADMIN_CREATABLE_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be one of " + ADMIN_CREATABLE_ROLES);
        }
        return createAccount(request.getUsername(), request.getEmail(), request.getPassword(), roleName);
    }

    private UserSummaryResponse createAccount(String username, String email, String password, String roleName) {
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        if (email != null && !email.isBlank() && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(email)
                .role(findOrCreateRole(roleName))
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

    public enum AdminSeedResult { CREATED, ALREADY_EXISTS, USERNAME_TAKEN_BY_NON_ADMIN }

    // The only way an ADMIN account comes into existence. Idempotent across restarts, and an
    // existing account is never modified - no silent password resets or role promotions.
    public AdminSeedResult ensureAdminAccount(String username, String password, String email) {
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            return "ROLE_ADMIN".equals(existing.get().getRole().getName())
                    ? AdminSeedResult.ALREADY_EXISTS
                    : AdminSeedResult.USERNAME_TAKEN_BY_NON_ADMIN;
        }

        userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(email)
                .role(findOrCreateRole("ADMIN"))
                .build());
        return AdminSeedResult.CREATED;
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
