package com.floww.server.auth.presentation.dto.response;

import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;

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

    public UserResponse withWallets(List<Object> linkedWallets) {
        return new UserResponse(userId, email, displayName, role, providers, linkedWallets, createdAt);
    }
}
