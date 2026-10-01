package com.app.bookingservice;

import com.app.bookingservice.exception.DeclineReason;
import com.app.bookingservice.exception.ReservationDeclinedException;
import com.app.bookingservice.model.SeatState;
import com.app.bookingservice.model.SeatStatus;
import com.app.bookingservice.service.ReservationService;
import com.app.bookingservice.service.ReservationService.ReserveResult;
import com.app.bookingservice.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires real concurrent requests at the reserve flow against a real Postgres and checks
 * that the database-level guarantees hold.
 */
class ReservationConcurrencyTest extends IntegrationTest {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;

    @Test
    void hotSeat_exactlyOneWinner() throws Exception {
        UUID showId = createShow(10);

        List<Outcome> outcomes = runConcurrently(50, i ->
                () -> reservations.reserve("user-" + i, showId, List.of("A1"), "key-" + i));

        assertThat(count(outcomes, "created")).isEqualTo(1);
        assertThat(count(outcomes, DeclineReason.SEAT_TAKEN.code())).isEqualTo(49);
        assertThat(seatStatus(showId, "A1")).isEqualTo(SeatStatus.HELD);
        assertInvariant(showId);
    }

    @Test
    void perUserLimit_holdsUnderConcurrency() throws Exception {
        UUID showId = createShow(20);

        List<Outcome> outcomes = runConcurrently(10, i ->
                () -> reservations.reserve("alice", showId, List.of("A" + (i + 1)), "key-" + i));

        assertThat(count(outcomes, "created")).isEqualTo(ShowService.PER_USER_LIMIT);
        assertThat(count(outcomes, DeclineReason.PER_USER_LIMIT.code())).isEqualTo(10 - ShowService.PER_USER_LIMIT);
        assertThat(shows.get(showId).counts().held()).isEqualTo(ShowService.PER_USER_LIMIT);
        assertInvariant(showId);
    }

    @Test
    void idempotency_sameKeyReservesOnce() throws Exception {
        UUID showId = createShow(10);

        List<Outcome> outcomes = runConcurrently(10, i ->
                () -> reservations.reserve("alice", showId, List.of("A1", "A2"), "same-key"));

        assertThat(count(outcomes, "created")).isEqualTo(1);
        assertThat(count(outcomes, "replayed")).isEqualTo(9);
        assertThat(outcomes.stream().map(Outcome::reservationId).distinct()).hasSize(1);
        assertThat(shows.get(showId).counts().held()).isEqualTo(2);

        Outcome differentSeats = attempt(() -> reservations.reserve("alice", showId, List.of("A3"), "same-key"));
        assertThat(differentSeats.result()).isEqualTo(DeclineReason.IDEMPOTENCY_MISMATCH.code());
        assertInvariant(showId);
    }

    @Test
    void overlappingMultiSeatRequests_noDeadlockNoPartialHolds() throws Exception {
        UUID showId = createShow(10);

        // Neighbouring pairs requested in alternating order: [A1,A2], [A3,A2], [A3,A4], [A5,A4], ...
        List<Outcome> outcomes = runConcurrently(40, i -> {
            int first = (i % 9) + 1;
            List<String> pair = i % 2 == 0
                    ? List.of("A" + first, "A" + (first + 1))
                    : List.of("A" + (first + 1), "A" + first);
            return () -> reservations.reserve("user-" + i, showId, pair, "key-" + i);
        });

        long created = count(outcomes, "created");
        assertThat(created).isPositive();
        assertThat(count(outcomes, "created") + count(outcomes, DeclineReason.SEAT_TAKEN.code())).isEqualTo(40);
        // All-or-nothing: every successful reservation holds exactly its two seats.
        assertThat(shows.get(showId).counts().held()).isEqualTo(created * 2);
        assertInvariant(showId);
    }

    // --- helpers ---

    private UUID createShow(int seats) {
        List<String> seatNos = IntStream.rangeClosed(1, seats).mapToObj(n -> "A" + n).toList();
        return shows.create("test-show", seatNos, 25000).show().id();
    }

    /** Starts all tasks at the same instant on separate threads and collects every outcome. */
    private List<Outcome> runConcurrently(int n, Function<Integer, Callable<ReserveResult>> taskFor) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<ReserveResult> task = taskFor.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt(task);
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        }
    }

    /** "created" / "replayed" on success, the decline code on a clean decline; anything else fails the test. */
    private static Outcome attempt(Callable<ReserveResult> task) throws Exception {
        try {
            ReserveResult r = task.call();
            return new Outcome(r.created() ? "created" : "replayed", r.reservation().id());
        } catch (ReservationDeclinedException e) {
            return new Outcome(e.reason().code(), null);
        }
    }

    private static long count(List<Outcome> outcomes, String result) {
        return outcomes.stream().filter(o -> o.result().equals(result)).count();
    }

    private SeatStatus seatStatus(UUID showId, String seatNo) {
        Map<String, SeatStatus> bySeat = shows.get(showId).seats().stream()
                .collect(Collectors.toMap(SeatState::seatNo, SeatState::status));
        return bySeat.get(seatNo);
    }

    private void assertInvariant(UUID showId) {
        var state = shows.get(showId);
        var c = state.counts();
        assertThat(c.available() + c.held() + c.confirmed()).isEqualTo(state.show().totalSeats());
    }

    private record Outcome(String result, UUID reservationId) {
    }
}
