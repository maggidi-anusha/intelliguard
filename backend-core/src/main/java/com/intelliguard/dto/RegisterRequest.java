package com.intelliguard.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    @Email
    private String email;

    // Optional - only VIEWER is allowed (the default when omitted); USER and ADMIN -> 403.
    private String role;
}
