package com.floww.server.auth;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() { }

    public static void check(String password) {
        if (password == null) throw violation();
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || password.isBlank()
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw violation();
        }
    }

    private static ApiException violation() {
        return new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION);
    }
}