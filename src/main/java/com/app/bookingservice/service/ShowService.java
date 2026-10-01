package com.app.bookingservice.service;

import com.app.bookingservice.exception.InvalidRequestException;
import com.app.bookingservice.exception.NotFoundException;
import com.app.bookingservice.model.SeatState;
import com.app.bookingservice.model.SeatStatus;
import com.app.bookingservice.model.Show;
import com.app.bookingservice.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ShowService {

    public static final int PER_USER_LIMIT = 4;

    private final ShowRepository shows;

    public ShowService(ShowRepository shows) {
        this.shows = shows;
    }

    @Transactional
    public ShowState create(String name, List<String> seatNos, long pricePaise) {
        if (new HashSet<>(seatNos).size() != seatNos.size()) {
            throw new InvalidRequestException("seats must be unique");
        }
        Show show = new Show(UUID.randomUUID(), name, pricePaise, PER_USER_LIMIT, seatNos.size());
        shows.insert(show);
        shows.insertSeats(show.id(), seatNos);
        List<SeatState> seats = seatNos.stream().map(seat -> new SeatState(seat, SeatStatus.AVAILABLE)).toList();
        return new ShowState(show, seats, new Counts(seats.size(), 0, 0));
    }

    /** Per-seat status and counts, computed from a single query so the counts always add up. */
    @Transactional(readOnly = true)
    public ShowState get(UUID showId) {
        Show show = shows.findById(showId)
                .orElseThrow(() -> new NotFoundException("show not found"));
        List<SeatState> seats = shows.findSeatStates(showId);
        return new ShowState(show, seats, Counts.of(seats));
    }

    public record ShowState(Show show, List<SeatState> seats, Counts counts) {
    }

    public record Counts(int available, int held, int confirmed) {

        static Counts of(List<SeatState> seats) {
            int available = 0, held = 0, confirmed = 0;
            for (SeatState seat : seats) {
                switch (seat.status()) {
                    case AVAILABLE -> available++;
                    case HELD -> held++;
                    case CONFIRMED -> confirmed++;
                }
            }
            return new Counts(available, held, confirmed);
        }
    }
}
