package com.floww.server.auth.domain;

public enum UserStatus {
    ACTIVE,
    SUSPENDED;

    public boolean canSignIn() {
        return this == ACTIVE;
    }
}