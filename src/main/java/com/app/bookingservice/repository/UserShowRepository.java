package com.app.bookingservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class UserShowRepository {

    private final JdbcTemplate jdbc;

    public UserShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes a row lock on (user, show), creating the row first if needed. Held until the
     * transaction ends, so one user's concurrent reservations for a show run one at a time.
     */
    public void lock(String userId, UUID showId) {
        jdbc.update("INSERT INTO user_show (user_id, show_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, showId);
        jdbc.queryForList("SELECT 1 FROM user_show WHERE user_id = ? AND show_id = ? FOR UPDATE", userId, showId);
    }
}
