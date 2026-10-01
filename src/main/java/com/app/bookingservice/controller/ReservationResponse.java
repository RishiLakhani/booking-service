package com.app.bookingservice.controller;

import com.app.bookingservice.model.Reservation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        OffsetDateTime expiresAt) {

    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(),
                r.status().dbValue(), r.expiresAt());
    }
}
