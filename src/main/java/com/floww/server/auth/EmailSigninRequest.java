package com.floww.server.auth;

public record EmailSigninRequest(String email, String password) {

    @Override
    public String toString() {
        return "EmailSigninRequest[***]";
    }
}