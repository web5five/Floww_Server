package com.floww.server.auth;

public enum UserStatus {
    ACTIVE,
    SUSPENDED;

    public boolean canSignIn() {
        return this == ACTIVE;
    }
}