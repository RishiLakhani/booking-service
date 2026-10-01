package com.app.bookingservice.controller;

import com.app.bookingservice.exception.ForbiddenException;
import com.app.bookingservice.exception.InvalidRequestException;
import com.app.bookingservice.model.Role;
import com.app.bookingservice.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues tokens for testing and the burst script; stands in for a real login.
 * Anyone can get a user token; an admin token requires the X-Admin-Secret header.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request,
                               @RequestHeader(value = "X-Admin-Secret", required = false) String adminSecret) {
        Role role = parseRole(request.role());
        if (role == Role.ADMIN && !tokenService.isValidAdminSecret(adminSecret)) {
            throw new ForbiddenException("Admin token requires a valid X-Admin-Secret header");
        }
        TokenService.IssuedToken issued = tokenService.issue(request.userId(), role);
        return new TokenResponse(issued.token(), request.userId(), role.claimValue(), issued.expiresAt());
    }

    private static Role parseRole(String role) {
        if (role == null) {
            return Role.USER;
        }
        try {
            return Role.fromClaim(role);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("role must be 'user' or 'admin'");
        }
    }
}
