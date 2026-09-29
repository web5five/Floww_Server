package com.floww.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ADMIN_EMAIL=auth-admin@example.test",
                "ADMIN_PASSWORD=AdminPassword123!",
                "floww.auth.jwt.signing-key=auth-test-signing-key-at-least-32-bytes"
        })
class AuthHttpIntegrationTest {
    private static final String ADMIN_EMAIL = "auth-admin@example.test";
    private static final String ADMIN_PASSWORD = "AdminPassword123!";
    private static final String USER_PASSWORD = "StrongPassword123!";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate db;

    @Test
    void signupPersistsUserAndRejectsDuplicateOrClientSuppliedRole() throws Exception {
        String email = newEmail();

        ResponseEntity<String> created = post(
                "/api/v1/auth/email/signup",
                signupBody(email, USER_PASSWORD, "테스트 사용자"));

        assertEquals(201, created.getStatusCode().value());
        JsonNode response = json.readTree(created.getBody());
        assertEquals("Bearer", response.path("tokenType").asText());
        assertEquals(true, response.path("isNewUser").asBoolean());
        assertEquals("USER", response.path("user").path("role").asText());
        assertEquals("EMAIL", response.path("user").path("providers").get(0).asText());

        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email));
        assertEquals("USER", db.queryForObject(
                "SELECT role FROM users WHERE email = ?", String.class, email));

        ResponseEntity<String> duplicate = post(
                "/api/v1/auth/email/signup",
                signupBody(email, USER_PASSWORD, "중복 사용자"));
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals("EMAIL_ALREADY_EXISTS",
                json.readTree(duplicate.getBody()).path("reasonCode").asText());

        ResponseEntity<String> suppliedRole = post(
                "/api/v1/auth/email/signup",
                """
                {"email":"%s","password":"%s","role":"ADMIN"}
                """.formatted(newEmail(), USER_PASSWORD));
        assertEquals(400, suppliedRole.getStatusCode().value());
        assertEquals("INVALID_INPUT",
                json.readTree(suppliedRole.getBody()).path("reasonCode").asText());
    }

    @Test
    void clientSigninReturnsSameErrorForUnknownEmailAndWrongPassword() throws Exception {
        String email = newEmail();
        assertEquals(201, post(
                "/api/v1/auth/email/signup",
                signupBody(email, USER_PASSWORD, null)).getStatusCode().value());

        ResponseEntity<String> success = post(
                "/api/v1/auth/email/signin",
                signinBody(email, USER_PASSWORD));
        assertEquals(200, success.getStatusCode().value());
        assertEquals("client", json.readTree(success.getBody())
                .path("accessToken").asText().isBlank() ? "" : audience(success.getBody()));

        ResponseEntity<String> wrongPassword = post(
                "/api/v1/auth/email/signin",
                signinBody(email, "WrongPassword123!"));
        ResponseEntity<String> unknownEmail = post(
                "/api/v1/auth/email/signin",
                signinBody(newEmail(), USER_PASSWORD));

        assertEquals(401, wrongPassword.getStatusCode().value());
        assertEquals(401, unknownEmail.getStatusCode().value());
        assertEquals(json.readTree(wrongPassword.getBody()),
                json.readTree(unknownEmail.getBody()));
    }

    @Test
    void adminSigninRequiresAdminRole() throws Exception {
        ResponseEntity<String> adminSignin = post(
                "/api/v1/admin/auth/signin",
                signinBody(ADMIN_EMAIL, ADMIN_PASSWORD));

        assertEquals(200, adminSignin.getStatusCode().value());
        assertEquals("ADMIN",
                json.readTree(adminSignin.getBody()).path("user").path("role").asText());
        assertEquals("admin", audience(adminSignin.getBody()));

        String userEmail = newEmail();
        assertEquals(201, post(
                "/api/v1/auth/email/signup",
                signupBody(userEmail, USER_PASSWORD, null)).getStatusCode().value());

        ResponseEntity<String> userAtAdminSignin = post(
                "/api/v1/admin/auth/signin",
                signinBody(userEmail, USER_PASSWORD));

        assertEquals(401, userAtAdminSignin.getStatusCode().value());
        assertEquals("INVALID_CREDENTIALS",
                json.readTree(userAtAdminSignin.getBody()).path("reasonCode").asText());
    }

    @Test
    void profileWorksForUserAndAdminAndClientTokenCannotUseAdminRoutes() throws Exception {
        String userEmail = newEmail();
        assertEquals(201, post(
                "/api/v1/auth/email/signup",
                signupBody(userEmail, USER_PASSWORD, "일반 사용자")).getStatusCode().value());

        String userToken = token(post(
                "/api/v1/auth/email/signin",
                signinBody(userEmail, USER_PASSWORD)).getBody());

        ResponseEntity<String> userProfile = get("/api/v1/users/me", userToken);
        assertEquals(200, userProfile.getStatusCode().value());
        assertEquals(userEmail,
                json.readTree(userProfile.getBody()).path("email").asText());
        assertEquals("USER",
                json.readTree(userProfile.getBody()).path("role").asText());

        String adminToken = token(post(
                "/api/v1/admin/auth/signin",
                signinBody(ADMIN_EMAIL, ADMIN_PASSWORD)).getBody());

        ResponseEntity<String> adminProfile = get("/api/v1/users/me", adminToken);
        assertEquals(200, adminProfile.getStatusCode().value());
        assertEquals(ADMIN_EMAIL,
                json.readTree(adminProfile.getBody()).path("email").asText());
        assertEquals("ADMIN",
                json.readTree(adminProfile.getBody()).path("role").asText());

        assertEquals(403, get("/api/v1/admin/does-not-exist", userToken)
                .getStatusCode().value());
        assertEquals(404, get("/api/v1/admin/does-not-exist", adminToken)
                .getStatusCode().value());
    }

    @Test
    void protectedRouteRejectsMissingExpiredAndForgedTokens() throws Exception {
        assertEquals(401, get("/api/v1/users/me", null).getStatusCode().value());
        assertEquals(401, get("/api/v1/users/me", "not.a.valid.jwt")
                .getStatusCode().value());
        assertEquals(401, get("/api/v1/users/me", expiredToken())
                .getStatusCode().value());
    }

    @Test
    void userCannotReadAnotherUsersExecution() throws Exception {
        String aliceEmail = newEmail();
        String bobEmail = newEmail();

        assertEquals(201, post(
                "/api/v1/auth/email/signup",
                signupBody(aliceEmail, USER_PASSWORD, "Alice")).getStatusCode().value());
        assertEquals(201, post(
                "/api/v1/auth/email/signup",
                signupBody(bobEmail, USER_PASSWORD, "Bob")).getStatusCode().value());

        String aliceToken = token(post(
                "/api/v1/auth/email/signin",
                signinBody(aliceEmail, USER_PASSWORD)).getBody());
        String bobToken = token(post(
                "/api/v1/auth/email/signin",
                signinBody(bobEmail, USER_PASSWORD)).getBody());

        String expiresAt = Instant.now().plusSeconds(3600).toString();
        String mandate = """
                {
                  "confirmed": true,
                  "mandate": {
                    "goal": "Buy item-1 within budget",
                    "itemId": "item-1",
                    "maxTotal": "10.00",
                    "currency": "TEST_USDC",
                    "recipient": "merchant_good",
                    "expiresAt": "%s"
                  }
                }
                """.formatted(expiresAt);

        ResponseEntity<String> created = post(
                "/api/executions",
                mandate,
                aliceToken);
        assertEquals(200, created.getStatusCode().value());

        String executionId = json.readTree(created.getBody()).path("id").asText();
        assertFalse(executionId.isBlank());

        assertEquals(404, get(
                "/api/executions/" + executionId,
                bobToken).getStatusCode().value());
    }

    private String expiredToken() {
        Instant oldTime = Instant.parse("2000-01-01T00:00:00Z");
        User user = new User(
                UUID.randomUUID(),
                "expired@example.test",
                "unused-hash",
                UserRole.USER,
                UserStatus.ACTIVE,
                AuthProvider.EMAIL,
                null,
                oldTime,
                oldTime);

        JwtProvider oldClockProvider = new JwtProvider(
                "auth-test-signing-key-at-least-32-bytes",
                Clock.fixed(oldTime, ZoneOffset.UTC));

        return oldClockProvider.issue(user, TokenAudience.CLIENT).value();
    }

    private ResponseEntity<String> post(String path, String body) {
        return post(path, body, null);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return http.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers(token)),
                String.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static String signupBody(String email, String password, String displayName) {
        String name = displayName == null ? "" : ",\"displayName\":\"" + displayName + "\"";
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + name + "}";
    }

    private static String signinBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String token(String body) throws Exception {
        return new ObjectMapper().readTree(body).path("accessToken").asText();
    }

    private static String audience(String body) throws Exception {
        String accessToken = token(body);
        String payload = accessToken.split("\\.")[1];
        JsonNode claims = new ObjectMapper().readTree(
                java.util.Base64.getUrlDecoder().decode(payload));
        return claims.path("aud").asText();
    }

    private static String newEmail() {
        return "auth-test-" + UUID.randomUUID() + "@example.test";
    }
}