package com.app.bookingservice.repository;

import com.app.bookingservice.model.SeatState;
import com.app.bookingservice.model.SeatStatus;
import com.app.bookingservice.model.Show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {

    private static final RowMapper<Show> SHOW_MAPPER = (rs, rowNum) -> new Show(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"));

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update("""
                INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
                VALUES (?, ?, ?, ?, ?)
                """, show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats());
    }

    public void insertSeats(UUID showId, List<String> seatNos) {
        List<Object[]> rows = new ArrayList<>(seatNos.size());
        for (int i = 0; i < seatNos.size(); i++) {
            rows.add(new Object[]{showId, seatNos.get(i), i});
        }
        jdbc.batchUpdate("INSERT INTO seats (show_id, seat_no, position) VALUES (?, ?, ?)", rows);
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.query("SELECT * FROM shows WHERE id = ?", SHOW_MAPPER, id).stream().findFirst();
    }

    /** Number of the given seat numbers that exist in the show. */
    public int countExistingSeats(UUID showId, List<String> seatNos) {
        Integer count = jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT count(*) FROM seats WHERE show_id = ? AND seat_no = ANY(?)");
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", seatNos.toArray()));
            return ps;
        }, rs -> rs.next() ? rs.getInt(1) : 0);
        return count == null ? 0 : count;
    }

    /**
     * Effective status of every seat in one snapshot, in creation order.
     * A held seat whose hold has expired counts as available (lazy expiry).
     */
    public List<SeatState> findSeatStates(UUID showId) {
        return jdbc.query("""
                SELECT s.seat_no,
                       CASE
                           WHEN r.status = 'confirmed' THEN 'CONFIRMED'
                           WHEN r.status = 'held' AND r.expires_at > now() THEN 'HELD'
                           ELSE 'AVAILABLE'
                       END AS status
                  FROM seats s
                  LEFT JOIN reservation_seats rs ON rs.show_id = s.show_id AND rs.seat_no = s.seat_no
                  LEFT JOIN reservations r ON r.id = rs.reservation_id
                 WHERE s.show_id = ?
                 ORDER BY s.position
                """, (rs, rowNum) -> new SeatState(rs.getString("seat_no"), SeatStatus.valueOf(rs.getString("status"))),
                showId);
    }
}
