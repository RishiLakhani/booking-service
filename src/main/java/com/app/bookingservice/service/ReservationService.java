package com.app.bookingservice.service;

import com.app.bookingservice.config.ReservationProperties;
import com.app.bookingservice.exception.DeclineReason;
import com.app.bookingservice.exception.ForbiddenException;
import com.app.bookingservice.exception.InvalidRequestException;
import com.app.bookingservice.exception.NotFoundException;
import com.app.bookingservice.exception.ReservationDeclinedException;
import com.app.bookingservice.model.Reservation;
import com.app.bookingservice.model.ReservationStatus;
import com.app.bookingservice.model.Show;
import com.app.bookingservice.repository.ReservationRepository;
import com.app.bookingservice.repository.ReservationSeatRepository;
import com.app.bookingservice.repository.ShowRepository;
import com.app.bookingservice.repository.UserShowRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowRepository shows;
    private final UserShowRepository userShows;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationProperties props;

    public ReservationService(ShowRepository shows, UserShowRepository userShows,
                              ReservationRepository reservations, ReservationSeatRepository reservationSeats,
                              ReservationProperties props) {
        this.shows = shows;
        this.userShows = userShows;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.props = props;
    }

    /**
     * Places an all-or-nothing hold on the requested seats for the user.
     * Any decline throws ReservationDeclinedException, which rolls the whole transaction back.
     */
    @Transactional
    public ReserveResult reserve(String userId, UUID showId, List<String> seatNos, String idempotencyKey) {
        if (new HashSet<>(seatNos).size() != seatNos.size()) {
            throw new InvalidRequestException("seats must be unique");
        }
        List<String> sortedSeats = seatNos.stream().sorted().toList();

        Show show = shows.findById(showId)
                .orElseThrow(() -> new NotFoundException("show not found"));
        if (shows.countExistingSeats(showId, sortedSeats) != sortedSeats.size()) {
            throw new InvalidRequestException("unknown seat in request");
        }

        // Serializes this user's requests for this show: makes the limit check and idempotency check safe.
        userShows.lock(userId, showId);

        var existing = reservations.findByIdempotencyKey(userId, showId, idempotencyKey);
        if (existing.isPresent()) {
            Reservation original = existing.get();
            if (!original.seats().equals(sortedSeats)) {
                throw new ReservationDeclinedException(DeclineReason.IDEMPOTENCY_MISMATCH,
                        "Idempotency-Key was already used for different seats", userId, showId);
            }
            if (original.status() == ReservationStatus.EXPIRED) {
                throw new ReservationDeclinedException(DeclineReason.HOLD_EXPIRED,
                        "the hold for this Idempotency-Key has expired; retry with a new key", userId, showId);
            }
            return new ReserveResult(original, false);
        }

        int activeSeats = reservations.countActiveSeats(userId, showId);
        if (activeSeats + sortedSeats.size() > show.perUserLimit()) {
            throw new ReservationDeclinedException(DeclineReason.PER_USER_LIMIT,
                    "at most " + show.perUserLimit() + " seats per user for this show", userId, showId);
        }

        // Lazy expiry: free requested seats still owned by expired holds (status transition under row lock).
        List<UUID> expired = reservations.lockExpiredHoldsForSeats(showId, sortedSeats);
        if (!expired.isEmpty()) {
            reservations.markExpired(expired);
            reservationSeats.deleteByReservationIds(expired);
        }

        long amountPaise = Math.multiplyExact(show.pricePaise(), sortedSeats.size());
        Reservation held = reservations.insertHeld(UUID.randomUUID(), showId, userId, idempotencyKey,
                sortedSeats, amountPaise, props.holdTtl());
        try {
            // The atomic decision: uq_seat_taken lets exactly one reservation own each seat.
            reservationSeats.insertAll(held.id(), showId, sortedSeats);
        } catch (DuplicateKeyException e) {
            throw new ReservationDeclinedException(DeclineReason.SEAT_TAKEN, "one or more seats are already taken",
                    userId, showId);
        }
        return new ReserveResult(held, true);
    }

    /**
     * Confirms the caller's own hold. Safe to repeat: confirming an already-confirmed reservation
     * returns it unchanged. Races with lazy expiry are settled by the guarded update in the repository.
     */
    @Transactional
    public ConfirmResult confirm(String userId, UUID reservationId) {
        var confirmed = reservations.confirm(reservationId, userId);
        if (confirmed.isPresent()) {
            return new ConfirmResult(confirmed.get(), true);
        }
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("reservation not found"));
        if (!reservation.userId().equals(userId)) {
            throw new ForbiddenException("reservation belongs to another user");
        }
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            return new ConfirmResult(reservation, false);
        }
        throw new ReservationDeclinedException(DeclineReason.HOLD_EXPIRED, "the hold has expired; reserve again",
                userId, reservation.showId());
    }

    /** confirmedNow = false means it was already confirmed (a repeat confirm). */
    public record ConfirmResult(Reservation reservation, boolean confirmedNow) {
    }

    /** created = false means an idempotent replay of an earlier request. */
    public record ReserveResult(Reservation reservation, boolean created) {
    }
}
