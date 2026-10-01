package com.app.bookingservice.observability;

import com.app.bookingservice.exception.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reservation outcome counters. All reason labels are registered up front so they show as 0
 * before the first event. Callers increment only after the transaction has finished.
 */
@Component
public class ReservationMetrics {

    public static final String IDEMPOTENT_REPLAY = "idempotent-replay";

    private final Counter held;
    private final Counter confirmed;
    private final Map<String, Counter> declined;

    public ReservationMetrics(MeterRegistry registry) {
        held = Counter.builder("reservations.held")
                .description("New holds created (201)")
                .register(registry);
        confirmed = Counter.builder("reservations.confirmed")
                .description("Holds confirmed (held -> confirmed)")
                .register(registry);
        declined = Stream.concat(Stream.of(IDEMPOTENT_REPLAY), Arrays.stream(DeclineReason.values()).map(DeclineReason::code))
                .collect(Collectors.toMap(Function.identity(), reason -> Counter.builder("reservations.declined")
                        .description("Reservation requests declined or replayed, by reason")
                        .tag("reason", reason)
                        .register(registry)));
    }

    public void held() {
        held.increment();
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void declined(String reason) {
        declined.get(reason).increment();
    }
}
