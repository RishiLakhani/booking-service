package com.app.bookingservice.repository;

import com.app.bookingservice.model.Reservation;
import com.app.bookingservice.model.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> RESERVATION_MAPPER = (rs, rowNum) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            Arrays.asList((String[]) rs.getArray("seats").getArray()),
            rs.getLong("amount_paise"),
            ReservationStatus.fromDb(rs.getString("status")),
            rs.getObject("expires_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a held reservation expiring at DB now() + holdTtl and returns the stored row. */
    public Reservation insertHeld(UUID id, UUID showId, String userId, String idempotencyKey,
                                  List<String> sortedSeats, long amountPaise, Duration holdTtl) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO reservations (id, show_id, user_id, idempotency_key, seats, amount_paise, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'held', now() + make_interval(secs => ?))
                    RETURNING *
                    """);
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, idempotencyKey);
            ps.setArray(5, con.createArrayOf("text", sortedSeats.toArray()));
            ps.setLong(6, amountPaise);
            ps.setLong(7, holdTtl.toSeconds());
            return ps;
        }, RESERVATION_MAPPER).getFirst();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ?", RESERVATION_MAPPER, id).stream().findFirst();
    }

    public Optional<Reservation> findByIdempotencyKey(String userId, UUID showId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM reservations WHERE user_id = ? AND show_id = ? AND idempotency_key = ?",
                RESERVATION_MAPPER, userId, showId, idempotencyKey).stream().findFirst();
    }

    /** Seats the user currently holds (unexpired) or has confirmed in the show. */
    public int countActiveSeats(String userId, UUID showId) {
        Integer count = jdbc.queryForObject("""
                SELECT coalesce(sum(cardinality(seats)), 0)
                  FROM reservations
                 WHERE user_id = ? AND show_id = ?
                   AND (status = 'confirmed' OR (status = 'held' AND expires_at > now()))
                """, Integer.class, userId, showId);
        return count == null ? 0 : count;
    }

    /**
     * Locks expired holds that own any of the given seats, in id order (deterministic lock order).
     * Under READ COMMITTED the WHERE clause is re-checked after waiting for a lock, so a hold that
     * was confirmed concurrently is not returned.
     */
    public List<UUID> lockExpiredHoldsForSeats(UUID showId, List<String> seatNos) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT id
                      FROM reservations
                     WHERE id IN (SELECT reservation_id FROM reservation_seats WHERE show_id = ? AND seat_no = ANY(?))
                       AND status = 'held'
                       AND expires_at <= now()
                     ORDER BY id
                       FOR UPDATE
                    """);
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", seatNos.toArray()));
            return ps;
        }, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    /** Moves locked holds from held to expired; only rows still held are changed. */
    public int markExpired(List<UUID> reservationIds) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE reservations SET status = 'expired' WHERE id = ANY(?) AND status = 'held'");
            ps.setArray(1, con.createArrayOf("uuid", reservationIds.toArray()));
            return ps;
        });
    }

    /**
     * Confirms the user's own hold if it is still held and unexpired. Empty if the guard fails
     * (not found, not the owner, already confirmed, or expired).
     */
    public Optional<Reservation> confirm(UUID id, String userId) {
        return jdbc.query("""
                UPDATE reservations
                   SET status = 'confirmed'
                 WHERE id = ? AND user_id = ? AND status = 'held' AND expires_at > now()
                RETURNING *
                """, RESERVATION_MAPPER, id, userId).stream().findFirst();
    }
}
