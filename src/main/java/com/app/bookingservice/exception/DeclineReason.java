package com.app.bookingservice.exception;

/** Domain outcomes where a reservation is cleanly refused (409), each with a stable reason code. */
public enum DeclineReason {
    SEAT_TAKEN("seat-taken"),
    PER_USER_LIMIT("per-user-limit"),
    IDEMPOTENCY_MISMATCH("idempotency-mismatch"),
    HOLD_EXPIRED("hold-expired");

    private final String code;

    DeclineReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
