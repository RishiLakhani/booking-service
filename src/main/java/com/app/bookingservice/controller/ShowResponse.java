package com.app.bookingservice.controller;

import com.app.bookingservice.model.SeatState;
import com.app.bookingservice.service.ShowService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Counts counts,
        List<SeatView> seats) {

    public record Counts(int available, int held, int confirmed) {
    }

    public record SeatView(String seat, String status) {
    }

    public static ShowResponse from(ShowService.ShowState state) {
        var show = state.show();
        var counts = state.counts();
        return new ShowResponse(
                show.id(),
                show.name(),
                show.pricePaise(),
                show.perUserLimit(),
                show.totalSeats(),
                new Counts(counts.available(), counts.held(), counts.confirmed()),
                state.seats().stream().map(ShowResponse::toView).toList());
    }

    private static SeatView toView(SeatState seat) {
        return new SeatView(seat.seatNo(), seat.status().name().toLowerCase(Locale.ROOT));
    }
}
