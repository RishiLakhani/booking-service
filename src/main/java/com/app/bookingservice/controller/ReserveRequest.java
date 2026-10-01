package com.app.bookingservice.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** No user field: identity comes from the token only. */
public record ReserveRequest(@NotEmpty List<@NotBlank String> seats) {
}
