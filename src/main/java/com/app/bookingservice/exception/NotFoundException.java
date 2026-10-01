package com.app.bookingservice.exception;

/** Requested resource does not exist: 404 not-found. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
