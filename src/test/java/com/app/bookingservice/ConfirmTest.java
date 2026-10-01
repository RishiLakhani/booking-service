package com.app.bookingservice;

import com.app.bookingservice.exception.ForbiddenException;
import com.app.bookingservice.exception.NotFoundException;
import com.app.bookingservice.model.Reservation;
import com.app.bookingservice.model.ReservationStatus;
import com.app.bookingservice.model.SeatStatus;
import com.app.bookingservice.service.ReservationService;
import com.app.bookingservice.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmTest extends IntegrationTest {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;

    @Test
    void ownerConfirms_andRepeatReturnsSameReservation() {
        UUID showId = createShow();
        Reservation held = reservations.reserve("alice", showId, List.of("A1", "A2"), "k1").reservation();

        Reservation confirmed = reservations.confirm("alice", held.id());
        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(shows.get(showId).counts().confirmed()).isEqualTo(2);

        Reservation again = reservations.confirm("alice", held.id());
        assertThat(again.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(again.id()).isEqualTo(held.id());
    }

    @Test
    void nonOwnerIsForbidden_andHoldIsUnchanged() {
        UUID showId = createShow();
        Reservation held = reservations.reserve("alice", showId, List.of("A1"), "k1").reservation();

        assertThatThrownBy(() -> reservations.confirm("bob", held.id()))
                .isInstanceOf(ForbiddenException.class);
        assertThat(shows.get(showId).counts().held()).isEqualTo(1);
    }

    @Test
    void unknownReservationIsNotFound() {
        assertThatThrownBy(() -> reservations.confirm("alice", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void parallelConfirmsAllSucceed() throws Exception {
        UUID showId = createShow();
        Reservation held = reservations.reserve("alice", showId, List.of("A1"), "k1").reservation();

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Reservation>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reservations.confirm("alice", held.id());
                }));
            }
            start.countDown();
            for (Future<Reservation> f : futures) {
                assertThat(f.get().status()).isEqualTo(ReservationStatus.CONFIRMED);
            }
        }
        assertThat(shows.get(showId).seats().getFirst().status()).isEqualTo(SeatStatus.CONFIRMED);
    }

    private UUID createShow() {
        return shows.create("confirm-test", List.of("A1", "A2", "A3"), 25000).show().id();
    }
}
