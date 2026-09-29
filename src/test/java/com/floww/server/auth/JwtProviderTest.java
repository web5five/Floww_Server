package com.floww.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.auth.infrastructure.JwtProvider;
import org.junit.jupiter.api.Test;

/** Issue #22: AUTH-00 토큰 규칙 (sub·role·aud·iat·exp, 30분, aud=admin은 ADMIN만). */
class JwtProviderTest {
    private static final String KEY = "test-only-signing-key-0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-09-29T09:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JwtProvider at(Instant now) {
        return new JwtProvider(KEY, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User user(UserRole role) {
        return new User(UUID.randomUUID(), "a@b.co", "$2a$hash", role, UserStatus.ACTIVE,
                AuthProvider.EMAIL, null, T0, T0);
    }

    private static JsonNode payload(String token) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void clientTokenRoundTripsWithAuth00Claims() throws Exception {
        User user = user(UserRole.USER);
        JwtProvider.IssuedToken issued = at(T0).issue(user, TokenAudience.CLIENT);

        assertEquals(1800, issued.expiresInSeconds());
        JsonNode claims = payload(issued.value());
        assertEquals(user.id().toString(), claims.get("sub").asText());
        assertEquals("USER", claims.get("role").asText());
        assertTrue(claims.get("aud").isTextual(), "aud is a single string as in AUTH-00");
        assertEquals("client", claims.get("aud").asText());
        assertEquals(T0.getEpochSecond(), claims.get("iat").asLong());
        assertEquals(T0.getEpochSecond() + 1800, claims.get("exp").asLong());

        JwtProvider.VerifiedToken verified = at(T0.plusSeconds(60)).verify(issued.value()).orElseThrow();
        assertEquals(user.id(), verified.userId());
        assertEquals(UserRole.USER, verified.role());
        assertEquals(TokenAudience.CLIENT, verified.audience());
    }

    @Test
    void adminAudienceOnlyForAdminRole() {
        User admin = user(UserRole.ADMIN);
        String token = at(T0).issue(admin, TokenAudience.ADMIN).value();
        assertEquals(TokenAudience.ADMIN, at(T0).verify(token).orElseThrow().audience());

        // ADMIN 계정도 클라이언트 로그인에서는 client 토큰을 받는다.
        assertEquals(TokenAudience.CLIENT,
                at(T0).verify(at(T0).issue(admin, TokenAudience.CLIENT).value()).orElseThrow().audience());

        assertThrows(IllegalArgumentException.class,
                () -> at(T0).issue(user(UserRole.USER), TokenAudience.ADMIN));
    }

    @Test
    void expiresAfterThirtyMinutes() {
        String token = at(T0).issue(user(UserRole.USER), TokenAudience.CLIENT).value();
        assertTrue(at(T0.plus(Duration.ofMinutes(29))).verify(token).isPresent());
        assertTrue(at(T0.plus(Duration.ofMinutes(31))).verify(token).isEmpty());
    }

    @Test
    void rejectsTamperedForeignUnsignedAndGarbageTokens() throws Exception {
        String token = at(T0).issue(user(UserRole.USER), TokenAudience.CLIENT).value();
        String[] parts = token.split("\\.");
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();

        // role을 ADMIN으로 바꾸고 서명은 그대로
        String forgedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"USER\"", "\"ADMIN\"").replace("\"client\"", "\"admin\"");
        String forged = parts[0] + "." + b64.encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertTrue(at(T0).verify(forged).isEmpty());

        // 다른 키로 서명
        JwtProvider other = new JwtProvider("another-test-signing-key-0123456789ab", Clock.fixed(T0, ZoneOffset.UTC));
        assertTrue(at(T0).verify(other.issue(user(UserRole.USER), TokenAudience.CLIENT).value()).isEmpty());

        // alg=none
        String unsigned = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
        assertTrue(at(T0).verify(unsigned).isEmpty());

        assertTrue(at(T0).verify(null).isEmpty());
        assertTrue(at(T0).verify("").isEmpty());
        assertTrue(at(T0).verify("not.a.jwt").isEmpty());
        assertTrue(at(T0).verify("x".repeat(5000)).isEmpty());
    }

    @Test
    void keyConfiguration() {
        JwtProvider unconfigured = new JwtProvider("", Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> unconfigured.issue(user(UserRole.USER), TokenAudience.CLIENT));
        assertThrows(IllegalStateException.class, () -> unconfigured.verify("a.b.c"));
        assertThrows(IllegalStateException.class, () -> new JwtProvider("too-short", Clock.systemUTC()));
    }
}