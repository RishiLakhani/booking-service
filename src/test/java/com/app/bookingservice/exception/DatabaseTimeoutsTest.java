package com.app.bookingservice.exception;

import com.app.bookingservice.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DatabaseTimeoutsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApiExceptionHandler errors;

    @Test
    void pooledConnectionsHaveTimeouts() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("10s");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    @Test
    void lockWaitTimeoutBecomes503() throws Exception {
        jdbc.update("INSERT INTO shows (id, name, price_paise, total_seats) VALUES (gen_random_uuid(), 'lock-test', 1, 1)");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM shows WHERE name = 'lock-test' FOR UPDATE");
                locked.countDown();
                await(release);
            }));
            locked.await();
            try {
                var e = catchThrowableOfType(UncategorizedSQLException.class, () -> tx.executeWithoutResult(status ->
                        jdbc.queryForList("SELECT id FROM shows WHERE name = 'lock-test' FOR UPDATE")));
                assertThat(e.getSQLException().getSQLState()).isEqualTo("55P03");
                assertThat(errors.uncategorizedSql(e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            } finally {
                release.countDown();
                holder.get();
            }
        }
    }

    @Test
    void statementTimeoutBecomes503() {
        var e = catchThrowableOfType(QueryTimeoutException.class, () -> jdbc.execute("SELECT pg_sleep(11)"));
        assertThat(errors.databaseUnavailable(e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
