package com.company.attendance.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login accepts either the employee code or the email address. */
public record LoginRequest(
        @NotBlank(message = "Email or employee ID is required")
        @Size(max = 150, message = "Identifier is too long")
        String usernameOrEmail,

        @NotBlank(message = "Password is required")
        @Size(max = 128, message = "Password is too long")
        String password,

        boolean rememberMe
) {
}
