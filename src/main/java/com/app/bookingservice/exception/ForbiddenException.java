package com.app.bookingservice.exception;

/** Authenticated but not allowed: 403 forbidden. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
