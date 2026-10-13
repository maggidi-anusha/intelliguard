package com.intelliguard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Body of POST /api/admin/users (ADMIN only).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminCreateUserRequest {

    @NotBlank
    @Size(max = 100)
    private String username;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    // USER, VIEWER or ADMIN.
    @NotBlank
    private String role;
}
