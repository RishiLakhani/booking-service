package com.app.bookingservice.controller;

import java.time.Instant;

public record TokenResponse(String token, String userId, String role, Instant expiresAt) {
}
