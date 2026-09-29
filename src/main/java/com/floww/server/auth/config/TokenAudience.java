package com.floww.server.auth.config;

import java.util.Optional;

public enum TokenAudience {
    CLIENT("client"),
    ADMIN("admin");

    private final String claim;

    TokenAudience(String claim) {
        this.claim = claim;
    }

    public String claim() {
        return claim;
    }

    public static Optional<TokenAudience> fromClaim(String raw) {
        if (raw == null) return Optional.empty();
        for (TokenAudience aud : values()) {
            if (aud.claim.equals(raw)) return Optional.of(aud);
        }
        return Optional.empty();
    }
}