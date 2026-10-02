package com.app.bookingservice.controller;

import com.app.bookingservice.exception.InvalidRequestException;
import com.app.bookingservice.observability.ReservationMetrics;
import com.app.bookingservice.service.ReservationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final ReservationService reservationService;
    private final ReservationMetrics metrics;

    public ReservationController(ReservationService reservationService, ReservationMetrics metrics) {
        this.reservationService = reservationService;
        this.metrics = metrics;
    }

    /** 201 for a new hold, 200 for an idempotent replay of the same request. */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                       @Valid @RequestBody ReserveRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        String idempotencyKey = resolveIdempotencyKey(headerKey, request.idempotencyKey());
        var result = reservationService.reserve(jwt.getSubject(), showId, request.seats(), idempotencyKey);
        var reservation = result.reservation();
        if (result.created()) {
            metrics.held();
        } else {
            metrics.declined(ReservationMetrics.IDEMPOTENT_REPLAY);
        }
        log.atInfo()
                .addKeyValue("event", "reserve")
                .addKeyValue("outcome", result.created() ? "held" : ReservationMetrics.IDEMPOTENT_REPLAY)
                .addKeyValue("user_id", reservation.userId())
                .addKeyValue("show_id", showId)
                .addKeyValue("reservation_id", reservation.id())
                .addKeyValue("seats", reservation.seats())
                .log("reservation {}", result.created() ? "held" : "replayed");
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReservationResponse.from(reservation));
    }

    /**
     * The key may be sent as the Idempotency-Key header or the idempotency_key body field (the brief allows
     * either). If both are present they must match.
     */
    private static String resolveIdempotencyKey(String headerKey, String bodyKey) {
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new InvalidRequestException("Idempotency-Key header and idempotency_key body field differ");
        }
        String key = headerKey != null ? headerKey : bodyKey;
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("an idempotency key is required (Idempotency-Key header or idempotency_key field)");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException("idempotency key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    /** Only the owner may confirm; repeating a confirm returns 200 with the confirmed reservation. */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        var result = reservationService.confirm(jwt.getSubject(), reservationId);
        if (result.confirmedNow()) {
            metrics.confirmed();
        }
        log.atInfo()
                .addKeyValue("event", "confirm")
                .addKeyValue("outcome", result.confirmedNow() ? "confirmed" : "already-confirmed")
                .addKeyValue("user_id", jwt.getSubject())
                .addKeyValue("reservation_id", reservationId)
                .log("reservation confirmed");
        return ReservationResponse.from(result.reservation());
    }
}
