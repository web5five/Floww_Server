package com.floww.server.auth;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class AuthInputs {
    /** AUTH-05: 최대 254자. */
    public static final int EMAIL_MAX_LENGTH = 254;
    /** AUTH-05: 최대 50자. */
    public static final int DISPLAY_NAME_MAX_LENGTH = 50;
    /** 로그인 비밀번호 상한. 정책 검사가 아니라 과도한 입력을 막기 위한 값이다. */
    private static final int SIGNIN_PASSWORD_MAX_LENGTH = 256;

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    private static final Set<String> SIGNUP_REQUIRED = Set.of("email", "password");
    private static final Set<String> SIGNUP_ALLOWED = Set.of("email", "password", "displayName");
    private static final Set<String> SIGNIN_FIELDS = Set.of("email", "password");

    private AuthInputs() { }

    /** AUTH-05. 형식 오류는 INVALID_INPUT, 비밀번호 규칙 위반은 PASSWORD_POLICY_VIOLATION. */
    public static EmailSignupRequest signup(JsonNode body) {
        fields(body, SIGNUP_REQUIRED, SIGNUP_ALLOWED);
        String email = normalizeEmail(text(body, "email"));
        if (!EMAIL.matcher(email).matches()) throw invalid();
        String password = text(body, "password");
        String displayName = displayName(body.get("displayName"));
        PasswordPolicy.check(password);
        return new EmailSignupRequest(email, password, displayName);
    }

    /**
     * AUTH-06, AUTH-11. 필드 누락·타입 오류만 INVALID_INPUT으로 거절한다.
     * 이메일 형식·비밀번호 규칙은 검사하지 않는다. 틀린 값은 조회 실패로 INVALID_CREDENTIALS가 된다.
     */
    public static EmailSigninRequest signin(JsonNode body) {
        fields(body, SIGNIN_FIELDS, SIGNIN_FIELDS);
        String email = normalizeEmail(text(body, "email"));
        String password = text(body, "password");
        if (email.isEmpty() || email.length() > EMAIL_MAX_LENGTH
                || password.isEmpty() || password.length() > SIGNIN_PASSWORD_MAX_LENGTH) {
            throw invalid();
        }
        return new EmailSigninRequest(email, password);
    }

    /** 저장·조회에 쓰는 이메일 형태. 가입·로그인·어드민 생성 모두 이 메서드를 거친다. */
    public static String normalizeEmail(String raw) {
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > EMAIL_MAX_LENGTH) throw invalid();
        return email;
    }

    private static String displayName(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        String value = node.textValue().strip();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > DISPLAY_NAME_MAX_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static void fields(JsonNode body, Set<String> required, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) throw invalid();
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_INPUT);
    }
}