package com.intelliguard.config;

import com.intelliguard.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Provisions the simulator's ADMIN account from environment config at startup. Public
// registration can't create ADMIN accounts (see AuthService.register), so this is the only
// path to one. With no password configured (e.g. tests, or a deployment that doesn't run the
// simulator) seeding is skipped rather than creating an admin with a blank/default password.
@Slf4j
@Component
public class AdminAccountSeeder implements ApplicationRunner {

    private final AuthService authService;
    private final String username;
    private final String password;
    private final String email;

    public AdminAccountSeeder(AuthService authService,
                              @Value("${intelliguard.seed-admin.username}") String username,
                              @Value("${intelliguard.seed-admin.password:}") String password,
                              @Value("${intelliguard.seed-admin.email:}") String email) {
        this.authService = authService;
        this.username = username;
        this.password = password;
        this.email = email.isBlank() ? null : email;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.isBlank()) {
            log.warn("No admin password configured (SIMULATOR_ADMIN_PASSWORD) - skipping admin "
                    + "account seeding; the simulator will not be able to ingest telemetry");
            return;
        }

        switch (authService.ensureAdminAccount(username, password, email)) {
            case CREATED -> log.info("Seeded ADMIN account '{}'", username);
            case ALREADY_EXISTS -> log.info("ADMIN account '{}' already exists - left unchanged", username);
            case USERNAME_TAKEN_BY_NON_ADMIN -> log.error("Username '{}' is already taken by a non-ADMIN "
                    + "account - not modifying it; the simulator's ingestion calls will be rejected", username);
        }
    }
}
