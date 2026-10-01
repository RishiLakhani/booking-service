package com.app.bookingservice.controller;

import com.app.bookingservice.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest extends IntegrationTest {

    private static final String ADMIN_SECRET = "test-admin-secret";

    @Autowired
    MockMvc mvc;

    @Test
    void userTokenByDefault() throws Exception {
        requestToken("{\"user_id\":\"alice\"}", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expires_at").isNotEmpty());
    }

    @Test
    void adminTokenWithValidSecret() throws Exception {
        requestToken("{\"user_id\":\"ops\",\"role\":\"admin\"}", ADMIN_SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void adminTokenWithoutOrWithWrongSecretIsForbidden() throws Exception {
        requestToken("{\"user_id\":\"ops\",\"role\":\"admin\"}", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        requestToken("{\"user_id\":\"ops\",\"role\":\"admin\"}", "wrong")
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        requestToken("{\"user_id\":\"x\",\"role\":\"superuser\"}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid-request"))
                .andExpect(jsonPath("$.message").value("role must be 'user' or 'admin'"));
        requestToken("{\"user_id\":\"\"}", null).andExpect(status().isBadRequest());
        requestToken("{}", null).andExpect(status().isBadRequest());
    }

    private ResultActions requestToken(String body, String adminSecret) throws Exception {
        var request = post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body);
        if (adminSecret != null) {
            request.header("X-Admin-Secret", adminSecret);
        }
        return mvc.perform(request);
    }
}
