package com.app.bookingservice.security;

import com.app.bookingservice.IntegrationTest;
import com.app.bookingservice.model.Role;
import com.app.bookingservice.service.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Access rules and the JSON bodies of Spring Security's own 401/403 responses. */
class SecurityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TokenService tokens;
    @Autowired
    JwtEncoder encoder;
    @Autowired
    ShowService shows;

    @Test
    void missingTamperedOrExpiredTokenIsUnauthorized() throws Exception {
        UUID showId = createShow();
        String valid = tokens.issue("alice", Role.USER).token();

        for (String bad : new String[]{null, valid.substring(0, valid.length() - 2) + "xx", expiredToken()}) {
            var request = get("/shows/" + showId);
            if (bad != null) {
                request.header("Authorization", "Bearer " + bad);
            }
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }
    }

    @Test
    void roleRules() throws Exception {
        String user = "Bearer " + tokens.issue("alice", Role.USER).token();
        String admin = "Bearer " + tokens.issue("ops", Role.ADMIN).token();
        String body = "{\"name\":\"s\",\"seats\":[\"A1\"],\"price_paise\":1}";

        mvc.perform(post("/shows").header("Authorization", user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(post("/shows").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get("/shows/" + createShow()).header("Authorization", user))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    /** Expired well beyond the decoder's 60-second clock-skew allowance. */
    private String expiredToken() {
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("alice").issuedAt(past.minus(Duration.ofHours(2))).expiresAt(past).claim("role", "user").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private UUID createShow() {
        return shows.create("security-test", List.of("A1"), 100).show().id();
    }
}
