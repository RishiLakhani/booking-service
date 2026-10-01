package com.app.bookingservice.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> seats,
        @NotNull @PositiveOrZero Long pricePaise) {
}
