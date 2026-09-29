package com.floww.server.auth;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,
        String email,
        String passwordHash,
        UserRole role,
        UserStatus status,
        AuthProvider provider,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {

    @Override
    public String toString() {
        return "User[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}