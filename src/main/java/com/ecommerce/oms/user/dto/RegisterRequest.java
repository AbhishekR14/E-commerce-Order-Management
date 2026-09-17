package com.ecommerce.oms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = PASSWORD_PATTERN, message = "must contain at least one letter and one digit")
        String password,
        @NotBlank @Size(max = 150) String fullName) {

    /** At least one letter and one digit; length is checked by @Size. BCrypt ignores input beyond 72 bytes. */
    public static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*[0-9]).*$";
}
