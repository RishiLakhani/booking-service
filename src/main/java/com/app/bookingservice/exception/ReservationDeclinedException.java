package com.app.bookingservice.exception;

public class ReservationDeclinedException extends RuntimeException {

    private final DeclineReason reason;

    public ReservationDeclinedException(DeclineReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeclineReason reason() {
        return reason;
    }
}
