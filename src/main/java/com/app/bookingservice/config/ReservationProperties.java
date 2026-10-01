package com.app.bookingservice.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.reservation")
public record ReservationProperties(@NotNull Duration holdTtl) {
}
