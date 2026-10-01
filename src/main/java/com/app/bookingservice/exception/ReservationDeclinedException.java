package com.app.bookingservice.exception;

import java.util.UUID;

/** A clean 409 decline. Carries who and which show, so the decline can be logged with context. */
public class ReservationDeclinedException extends RuntimeException {

    private final DeclineReason reason;
    private final String userId;
    private final UUID showId;

    public ReservationDeclinedException(DeclineReason reason, String message, String userId, UUID showId) {
        super(message);
        this.reason = reason;
        this.userId = userId;
        this.showId = showId;
    }

    public DeclineReason reason() {
        return reason;
    }

    public String userId() {
        return userId;
    }

    public UUID showId() {
        return showId;
    }
}
