package com.app.bookingservice.model;

import java.util.Locale;

public enum Role {
    USER,
    ADMIN;

    /** Value stored in the JWT "role" claim. */
    public String claimValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromClaim(String value) {
        for (Role role : values()) {
            if (role.claimValue().equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + value);
    }
}
