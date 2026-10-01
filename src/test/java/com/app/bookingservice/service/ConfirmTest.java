package com.app.bookingservice.service;

import com.app.bookingservice.IntegrationTest;
import com.app.bookingservice.exception.ForbiddenException;
import com.app.bookingservice.exception.NotFoundException;
import com.app.bookingservice.model.Reservation;
import com.app.bookingservice.model.ReservationStatus;
import com.app.bookingservice.model.SeatStatus;
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

        Reservation confirmed = reservations.confirm("alice", held.id()).reservation();
        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(shows.get(showId).counts().confirmed()).isEqualTo(2);

        Reservation again = reservations.confirm("alice", held.id()).reservation();
        assertThat(again.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(again.id()).isEqualTo(held.id());
    }

    @Test
    void amountIsPriceTimesSeats_andStaysTheSameOnReplayAndConfirm() {
        UUID showId = createShow(); // 25000 paise per seat
        Reservation pair = reservations.reserve("alice", showId, List.of("A1", "A2"), "k1").reservation();
        assertThat(pair.amountPaise()).isEqualTo(50_000);

        assertThat(reservations.reserve("alice", showId, List.of("A2", "A1"), "k1").reservation().amountPaise())
                .as("replay returns the stored amount").isEqualTo(50_000);
        assertThat(reservations.confirm("alice", pair.id()).reservation().amountPaise()).isEqualTo(50_000);

        Reservation single = reservations.reserve("bob", showId, List.of("A3"), "k2").reservation();
        assertThat(single.amountPaise()).isEqualTo(25_000);
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
                    return reservations.confirm("alice", held.id()).reservation();
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
