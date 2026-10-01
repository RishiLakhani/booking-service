package com.app.bookingservice.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        String idempotencyKey,
        List<String> seats,
        long amountPaise,
        ReservationStatus status,
        OffsetDateTime expiresAt) {
}
