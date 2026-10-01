package com.app.bookingservice.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401/403 raised by Spring Security (before any controller runs) in the API's error format.
 * The standard bearer-token handlers still set the status and WWW-Authenticate header.
 */
final class JsonSecurityErrorHandlers {

    private JsonSecurityErrorHandlers() {
    }

    static AuthenticationEntryPoint authenticationEntryPoint() {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, e) -> {
            bearer.commence(request, response, e);
            write(response, "unauthorized", "missing, invalid or expired token");
        };
    }

    static AccessDeniedHandler accessDeniedHandler() {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, e) -> {
            bearer.handle(request, response, e);
            write(response, "forbidden", "not allowed for this token");
        };
    }

    private static void write(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
