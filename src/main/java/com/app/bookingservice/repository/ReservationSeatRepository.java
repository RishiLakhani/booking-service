package com.app.bookingservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ReservationSeatRepository {

    private final JdbcTemplate jdbc;

    public ReservationSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims seats for a reservation, one insert per seat in the given (sorted) order. A seat
     * already owned by another reservation violates uq_seat_taken and throws DuplicateKeyException.
     * The sorted order keeps lock acquisition deterministic across concurrent multi-seat requests.
     */
    public void insertAll(UUID reservationId, UUID showId, List<String> sortedSeats) {
        for (String seatNo : sortedSeats) {
            jdbc.update("INSERT INTO reservation_seats (reservation_id, show_id, seat_no) VALUES (?, ?, ?)",
                    reservationId, showId, seatNo);
        }
    }

    public int deleteByReservationIds(List<UUID> reservationIds) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM reservation_seats WHERE reservation_id = ANY(?)");
            ps.setArray(1, con.createArrayOf("uuid", reservationIds.toArray()));
            return ps;
        });
    }
}
