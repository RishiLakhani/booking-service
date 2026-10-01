package com.app.bookingservice.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank(message = "JWT_SECRET must be set") @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String jwtSecret,
        @NotBlank(message = "ADMIN_SECRET must be set") String adminSecret,
        @NotNull Duration tokenTtl) {
}
