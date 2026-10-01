package com.app.bookingservice;

import com.app.bookingservice.model.Role;
import com.app.bookingservice.security.TokenService;
import com.app.bookingservice.service.ShowService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Metrics must reconcile with the responses clients see and with the show state. */
@AutoConfigureMockMvc
@AutoConfigureMetrics // tests disable metric exporters by default; this keeps /actuator/prometheus
class ObservabilityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MeterRegistry registry;
    @Autowired
    TokenService tokens;
    @Autowired
    ShowService shows;

    @Test
    void countersAndGaugeReconcileWithResponses() throws Exception {
        String admin = token("ops", Role.ADMIN);
        String alice = token("alice", Role.USER);
        String bob = token("bob", Role.USER);
        double held = counter("reservations.held", null);
        double confirmed = counter("reservations.confirmed", null);
        double replays = counter("reservations.declined", "idempotent-replay");
        double seatTaken = counter("reservations.declined", "seat-taken");

        String created = mvc.perform(auth(post("/shows"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"metrics\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":100}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID showId = UUID.fromString(created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

        String reserved = mvc.perform(reserve(showId, alice, "k1", "A1")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = reserved.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");
        mvc.perform(reserve(showId, alice, "k1", "A1")).andExpect(status().isOk());
        mvc.perform(reserve(showId, bob, "b1", "A1")).andExpect(status().isConflict());
        mvc.perform(auth(post("/reservations/" + reservationId + "/confirm"), alice)).andExpect(status().isOk());
        mvc.perform(auth(post("/reservations/" + reservationId + "/confirm"), alice)).andExpect(status().isOk());

        assertThat(counter("reservations.held", null) - held).isEqualTo(1);
        assertThat(counter("reservations.declined", "idempotent-replay") - replays).isEqualTo(1);
        assertThat(counter("reservations.declined", "seat-taken") - seatTaken).isEqualTo(1);
        assertThat(counter("reservations.confirmed", null) - confirmed).as("repeat confirm is not counted").isEqualTo(1);

        Thread.sleep(1100); // gauge snapshot is cached for a second
        double gauge = registry.get("seats.available").tag("show_id", showId.toString()).gauge().value();
        assertThat(gauge).isEqualTo(shows.get(showId).counts().available()).isEqualTo(2);

        String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .contains("reservations_held_total")
                .contains("reservations_confirmed_total")
                .contains("reservations_declined_total{reason=\"seat-taken\"}")
                .contains("reservations_declined_total{reason=\"hold-expired\"}")
                .contains("seats_available{show_id=\"" + showId + "\"} 2.0");
    }

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", "trace-123"))
                .andExpect(header().string("X-Request-Id", "trace-123"));
        String generated = mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", "bad id with spaces"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(generated).isNotEqualTo("bad id with spaces").hasSize(36);
        mvc.perform(get("/shows/" + UUID.randomUUID())).andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"));
    }

    private MockHttpServletRequestBuilder reserve(UUID showId, String token, String key, String seat) {
        return auth(post("/shows/" + showId + "/reserve"), token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"" + seat + "\"]}");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(String userId, Role role) {
        return tokens.issue(userId, role).token();
    }

    private double counter(String name, String reason) {
        var search = registry.get(name);
        return (reason == null ? search : search.tag("reason", reason)).counter().count();
    }
}
