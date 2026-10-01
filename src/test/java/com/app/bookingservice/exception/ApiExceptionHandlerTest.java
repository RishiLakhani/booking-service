package com.app.bookingservice.exception;

import com.app.bookingservice.IntegrationTest;
import com.app.bookingservice.model.Role;
import com.app.bookingservice.security.TokenService;
import com.app.bookingservice.service.ShowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every error leaves the API as {"error", "message"} with the right status. */
class ApiExceptionHandlerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TokenService tokens;
    @Autowired
    ShowService shows;
    @Autowired
    ApiExceptionHandler handler;

    private String user;
    private String admin;
    private UUID showId;

    @BeforeEach
    void setUp() {
        user = "Bearer " + tokens.issue("alice", Role.USER).token();
        admin = "Bearer " + tokens.issue("ops", Role.ADMIN).token();
        showId = shows.create("errors-test", List.of("A1", "A2"), 100).show().id();
    }

    @Test
    void badRequests() throws Exception {
        expectError(reserve("k1", "{\"seats\":"), 400, "invalid-request",
                "request body is missing, is not valid JSON, or has a value of the wrong type");
        expectError(reserve("k1", "{\"seats\":[]}"), 400, "invalid-request",
                "request body is missing required fields or has invalid values");
        expectError(reserve("k1", "{\"seats\":[\"A1\",\"A1\"]}"), 400, "invalid-request", "seats must be unique");
        expectError(reserve("k1", "{\"seats\":[\"Z9\"]}"), 400, "invalid-request", "unknown seat in request");
        expectError(reserve("x".repeat(256), "{\"seats\":[\"A1\"]}"), 400, "invalid-request",
                "Idempotency-Key must be 1-255 characters");
        expectError(post("/shows/" + showId + "/reserve").header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"),
                400, "invalid-request", "missing required header: Idempotency-Key");
        expectError(get("/shows/not-a-uuid").header("Authorization", user), 400, "invalid-request", "invalid value for 'id'");
        expectError(post("/shows").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":1.5}"),
                400, "invalid-request", "request body is missing, is not valid JSON, or has a value of the wrong type");
        expectError(post("/shows").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":1}"),
                400, "invalid-request", "seats must be unique");
    }

    @Test
    void notFoundMethodAndMediaType() throws Exception {
        expectError(get("/shows/" + UUID.randomUUID()).header("Authorization", user), 404, "not-found", "show not found");
        expectError(post("/reservations/" + UUID.randomUUID() + "/confirm").header("Authorization", user),
                404, "not-found", "reservation not found");
        expectError(get("/no-such-path").header("Authorization", user), 404, "not-found", "resource not found");
        expectError(delete("/shows/" + showId).header("Authorization", user), 405, "method-not-allowed", "method not allowed");
        expectError(post("/shows/" + showId + "/reserve").header("Authorization", user).header("Idempotency-Key", "k")
                .contentType(MediaType.TEXT_PLAIN).content("A1"), 415, "unsupported-media-type", "unsupported media type");
    }

    @Test
    void conflictCarriesReasonCode() throws Exception {
        mvc.perform(reserve("k1", "{\"seats\":[\"A1\"]}")).andExpect(status().isCreated());
        String bob = "Bearer " + tokens.issue("bob", Role.USER).token();
        expectError(post("/shows/" + showId + "/reserve").header("Authorization", bob).header("Idempotency-Key", "b1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"),
                409, "seat-taken", "one or more seats are already taken");
    }

    @Test
    void unexpectedErrorsHideInternals() {
        var unexpected = handler.unexpected(new IllegalStateException("secret detail"));
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.getBody().error()).isEqualTo("internal-error");
        assertThat(unexpected.getBody().message()).doesNotContain("secret");

        var otherSql = handler.uncategorizedSql(
                new UncategorizedSQLException("task", "SELECT 1", new SQLException("boom", "XX000")));
        assertThat(otherSql.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private MockHttpServletRequestBuilder reserve(String key, String body) {
        return post("/shows/" + showId + "/reserve").header("Authorization", user).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions expectError(MockHttpServletRequestBuilder request, int status, String code, String message)
            throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.message").value(message));
    }
}
