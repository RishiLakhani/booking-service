package com.app.bookingservice.controller;

import com.app.bookingservice.IntegrationTest;
import com.app.bookingservice.model.Role;
import com.app.bookingservice.security.TokenService;
import com.app.bookingservice.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The idempotency key works from the header or the body, and both identify the same request. */
class IdempotencyKeyPlacementTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TokenService tokens;
    @Autowired
    ShowService shows;

    @Test
    void bodyKeyWorks_andHeaderOrBodyReplayTheSameReservation() throws Exception {
        UUID showId = shows.create("key-placement", List.of("A1", "A2"), 25000).show().id();
        String token = tokens.issue("alice", Role.USER).token();

        String created = mvc.perform(reserve(showId, token, null, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = created.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");

        mvc.perform(reserve(showId, token, "k1", "{\"seats\":[\"A1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(reservationId));
        mvc.perform(reserve(showId, token, "k1", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(reservationId));
        mvc.perform(reserve(showId, token, null, "{\"seats\":[\"A2\"],\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency-mismatch"));

        assertThat(shows.get(showId).counts().held()).isEqualTo(1);
    }

    private static MockHttpServletRequestBuilder reserve(UUID showId, String token, String headerKey, String body) {
        var request = post("/shows/" + showId + "/reserve").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return headerKey == null ? request : request.header("Idempotency-Key", headerKey);
    }
}
