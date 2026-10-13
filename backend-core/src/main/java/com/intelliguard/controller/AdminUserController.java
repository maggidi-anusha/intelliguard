package com.intelliguard.controller;

import com.intelliguard.dto.AdminCreateUserRequest;
import com.intelliguard.dto.UserSummaryResponse;
import com.intelliguard.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Account administration. ADMIN only (see SecurityConfig): the only way to create USER (and,
// besides the startup seeder, ADMIN) accounts, since public registration creates VIEWERs only.
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AuthService authService;

    @PostMapping
    public ResponseEntity<UserSummaryResponse> create(@Valid @RequestBody AdminCreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.createUser(request));
    }
}
