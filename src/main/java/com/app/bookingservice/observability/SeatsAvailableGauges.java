package com.app.bookingservice.observability;

import com.app.bookingservice.repository.ShowRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * seats_available{show_id} per show, read from the database at scrape time (holds expire lazily,
 * so only the DB knows the true count). One grouped query serves all shows, cached for a second.
 */
@Component
public class SeatsAvailableGauges {

    private static final Duration CACHE_TTL = Duration.ofSeconds(1);

    private final MeterRegistry registry;
    private final ShowRepository shows;
    private Map<UUID, Long> cached = Map.of();
    private Instant cachedAt = Instant.EPOCH;

    public SeatsAvailableGauges(MeterRegistry registry, ShowRepository shows) {
        this.registry = registry;
        this.shows = shows;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        shows.findAllIds().forEach(this::register);
    }

    public void register(UUID showId) {
        Gauge.builder("seats.available", () -> availableSeats(showId))
                .description("Seats currently available, per show")
                .tag("show_id", showId.toString())
                .register(registry);
    }

    private double availableSeats(UUID showId) {
        try {
            return snapshot().getOrDefault(showId, 0L);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private synchronized Map<UUID, Long> snapshot() {
        if (Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
            cached = shows.countAvailableByShow();
            cachedAt = Instant.now();
        }
        return cached;
    }
}
