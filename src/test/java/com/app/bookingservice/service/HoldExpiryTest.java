package com.app.bookingservice.service;

import com.app.bookingservice.IntegrationTest;
import com.app.bookingservice.exception.DeclineReason;
import com.app.bookingservice.exception.ReservationDeclinedException;
import com.app.bookingservice.model.Reservation;
import com.app.bookingservice.model.SeatStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses a 1-second hold so expiry can be observed. */
@TestPropertySource(properties = "app.reservation.hold-ttl=1s")
class HoldExpiryTest extends IntegrationTest {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;

    @Test
    void expiredHoldCannotBeConfirmed_andSeatIsRebookable() throws Exception {
        UUID showId = createShow();
        Reservation held = reservations.reserve("alice", showId, List.of("A1"), "k1").reservation();

        waitForExpiry();

        assertDeclined(() -> reservations.confirm("alice", held.id()), DeclineReason.HOLD_EXPIRED);
        assertDeclined(() -> reservations.reserve("alice", showId, List.of("A1"), "k1"), DeclineReason.HOLD_EXPIRED);
        assertThat(reservations.reserve("bob", showId, List.of("A1"), "b1").created()).isTrue();
    }

    @Test
    void confirmedSeatIsNeverReleased() throws Exception {
        UUID showId = createShow();
        Reservation held = reservations.reserve("alice", showId, List.of("A1"), "k1").reservation();
        reservations.confirm("alice", held.id());

        waitForExpiry();

        assertDeclined(() -> reservations.reserve("bob", showId, List.of("A1"), "b1"), DeclineReason.SEAT_TAKEN);
        assertThat(shows.get(showId).seats().getFirst().status()).isEqualTo(SeatStatus.CONFIRMED);
    }

    private UUID createShow() {
        return shows.create("expiry-test", List.of("A1", "A2"), 25000).show().id();
    }

    private static void waitForExpiry() throws InterruptedException {
        Thread.sleep(1500);
    }

    private static void assertDeclined(Runnable action, DeclineReason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ReservationDeclinedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
