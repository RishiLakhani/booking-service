package com.app.bookingservice.security;

import com.app.bookingservice.model.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final AuthProperties props;

    public TokenService(JwtEncoder encoder, AuthProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public IssuedToken issue(String userId, Role role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("booking-service")
                .subject(userId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("role", role.claimValue())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** Constant-time comparison against ADMIN_SECRET. */
    public boolean isValidAdminSecret(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                props.adminSecret().getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
