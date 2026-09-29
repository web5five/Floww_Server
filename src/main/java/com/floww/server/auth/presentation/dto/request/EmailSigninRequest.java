package com.floww.server.auth.presentation.dto.request;

public record EmailSigninRequest(String email, String password) {

    @Override
    public String toString() {
        return "EmailSigninRequest[***]";
    }
}