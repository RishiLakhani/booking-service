package com.app.bookingservice.exception;

/** Invalid input: 400 invalid-request. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
