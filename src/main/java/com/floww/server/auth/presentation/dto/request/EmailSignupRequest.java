package com.floww.server.auth.presentation.dto.request;

public record EmailSignupRequest(String email, String password, String displayName) {

    @Override
    public String toString() {
        return "EmailSignupRequest[***]";
    }
}