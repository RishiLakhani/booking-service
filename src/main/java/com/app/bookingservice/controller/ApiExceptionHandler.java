package com.app.bookingservice.controller;

import com.app.bookingservice.service.ReservationDeclinedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ReservationDeclinedException.class)
    public ResponseEntity<ErrorResponse> declined(ReservationDeclinedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.reason().code(), e.getMessage()));
    }

    public record ErrorResponse(String error, String message) {
    }
}
