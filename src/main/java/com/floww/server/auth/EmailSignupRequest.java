package com.floww.server.auth;

public record EmailSignupRequest(String email, String password, String displayName) {

    @Override
    public String toString() {
        return "EmailSignupRequest[***]";
    }
}