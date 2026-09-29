package com.floww.server.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String email,
        String displayName,
        UserRole role,
        List<AuthProvider> providers,
        List<Object> wallets,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                List.of(user.provider()),
                List.of(),
                user.createdAt());
    }
}