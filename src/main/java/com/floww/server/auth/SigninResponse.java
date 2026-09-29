package com.floww.server.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SigninResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        @JsonProperty("isNewUser") boolean isNewUser,
        UserResponse user) {

    public static final String BEARER = "Bearer";

    public static SigninResponse of(String accessToken, long expiresInSeconds, boolean isNewUser, User user) {
        return new SigninResponse(accessToken, BEARER, expiresInSeconds, isNewUser, UserResponse.from(user));
    }

    @Override
    public String toString() {
        return "SigninResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn
                + ", isNewUser=" + isNewUser + ", user=" + (user == null ? null : user.userId()) + "]";
    }
}