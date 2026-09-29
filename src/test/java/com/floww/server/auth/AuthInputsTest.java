package com.floww.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.floww.server.auth.config.AuthInputs;
import com.floww.server.auth.domain.*;
import com.floww.server.auth.presentation.dto.request.EmailSigninRequest;
import com.floww.server.auth.presentation.dto.request.EmailSignupRequest;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Issue #22: AUTH-05·06 입력 검증, 비밀번호 규칙, 응답 DTO의 비밀정보 비노출. */
class AuthInputsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static ErrorCode code(Runnable call) {
        return assertThrows(ApiException.class, call::run).errorCode();
    }

    @Test
    void signupNormalizesEmailAndDisplayName() throws Exception {
        EmailSignupRequest req = AuthInputs.signup(json(
                "{\"email\":\"  User@Example.COM \",\"password\":\"password1\",\"displayName\":\"  홍길동 \"}"));
        assertEquals("user@example.com", req.email());
        assertEquals("password1", req.password());
        assertEquals("홍길동", req.displayName());
    }

    @Test
    void signupDisplayNameIsOptional() throws Exception {
        assertNull(AuthInputs.signup(json("{\"email\":\"a@b.co\",\"password\":\"password1\"}")).displayName());
        assertNull(AuthInputs.signup(json(
                "{\"email\":\"a@b.co\",\"password\":\"password1\",\"displayName\":\"   \"}")).displayName());
    }

    @Test
    void signupRejectsRoleAndUnknownFields() {
        // AUTH-00: 가입 요청에 role이 오면 400 INVALID_INPUT
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse(
                "{\"email\":\"a@b.co\",\"password\":\"password1\",\"role\":\"ADMIN\"}"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse(
                "{\"email\":\"a@b.co\",\"password\":\"password1\",\"status\":\"ACTIVE\"}"))));
    }

    @Test
    void signupRejectsMissingOrMalformedFields() {
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse("{\"email\":\"a@b.co\"}"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse(
                "{\"email\":\"not-an-email\",\"password\":\"password1\"}"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse(
                "{\"email\":\"a@b.co\",\"password\":12345678}"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse("[]"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signup(parse(
                "{\"email\":\"a@b.co\",\"password\":\"password1\",\"displayName\":\"" + "가".repeat(51) + "\"}"))));
    }

    @Test
    void passwordPolicy() {
        PasswordPolicy.check("12345678");
        PasswordPolicy.check("a".repeat(64));
        assertEquals(ErrorCode.PASSWORD_POLICY_VIOLATION, code(() -> PasswordPolicy.check("1234567")));
        assertEquals(ErrorCode.PASSWORD_POLICY_VIOLATION, code(() -> PasswordPolicy.check("a".repeat(65))));
        assertEquals(ErrorCode.PASSWORD_POLICY_VIOLATION, code(() -> PasswordPolicy.check("        ")));
        // 한글 25자 = 75바이트 > BCrypt 72바이트
        assertEquals(ErrorCode.PASSWORD_POLICY_VIOLATION, code(() -> PasswordPolicy.check("가".repeat(25))));
        PasswordPolicy.check("가".repeat(24));
    }

    @Test
    void signinOnlyChecksShapeNotPolicy() throws Exception {
        EmailSigninRequest req = AuthInputs.signin(json("{\"email\":\"Admin@Floww.io\",\"password\":\"short\"}"));
        assertEquals("admin@floww.io", req.email());
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signin(parse("{\"email\":\"a@b.co\"}"))));
        assertEquals(ErrorCode.INVALID_INPUT, code(() -> AuthInputs.signin(parse(
                "{\"email\":\"a@b.co\",\"password\":\"x\",\"role\":\"ADMIN\"}"))));
    }

    @Test
    void secretsNeverAppearInToStringOrResponse() throws Exception {
        User user = new User(UUID.randomUUID(), "a@b.co", "$2a$10$hash", UserRole.USER, UserStatus.ACTIVE,
                AuthProvider.EMAIL, null, Instant.parse("2026-09-29T09:00:00Z"), Instant.parse("2026-09-29T09:00:00Z"));
        assertFalse(user.toString().contains("$2a$"));
        assertFalse(new EmailSignupRequest("a@b.co", "password1", null).toString().contains("password1"));
        assertFalse(new EmailSigninRequest("a@b.co", "password1").toString().contains("password1"));

        SigninResponse response = SigninResponse.of("token-value", 1800, true, user);
        assertFalse(response.toString().contains("token-value"));

        JsonNode body = MAPPER.valueToTree(response);
        assertEquals("Bearer", body.get("tokenType").asText());
        assertEquals(true, body.get("isNewUser").asBoolean());
        assertFalse(body.has("newUser"));
        JsonNode u = body.get("user");
        assertFalse(u.has("passwordHash"));
        assertEquals("USER", u.get("role").asText());
        assertEquals(List.of("EMAIL"), List.of(u.get("providers").get(0).asText()));
        assertEquals(0, u.get("wallets").size());
    }

    private static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}