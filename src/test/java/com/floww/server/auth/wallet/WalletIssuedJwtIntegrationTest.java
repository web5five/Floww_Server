package com.floww.server.auth.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.common.auth.DevAuthFilter;
import com.floww.server.common.auth.JwtAuthFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ADMIN_EMAIL=auth-admin@example.test",
        "ADMIN_PASSWORD=AdminPassword123!",
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.auth.wallet.enabled=true",
        "floww.auth.wallet.origin=http://127.0.0.1:8080",
        "floww.auth.wallet.chain-ids=11155111",
        "floww.merchant.test-base-url="
})
class WalletIssuedJwtIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired Environment environment;

    private record WalletSession(String token, String userId, String walletId, String address,
                                 String walletType, boolean primary) { }

    @Test
    void issuedWalletJwtIdentifiesUserAndCanCreateReadAndRunOwnExecution() throws Exception {
        assertFalse(environment.acceptsProfiles(Profiles.of("dev")));
        assertEquals(1, context.getBeanNamesForType(JwtAuthFilter.class).length);
        assertEquals(0, context.getBeanNamesForType(DevAuthFilter.class).length);

        WalletSession owner = login();
        JsonNode profile = body(request(HttpMethod.GET, "/api/v1/users/me", owner.token(), null, null), 200);
        assertEquals(owner.userId(), profile.path("userId").asText());
        assertEquals("USER", profile.path("role").asText());
        assertEquals("WALLET", profile.path("providers").get(0).asText());
        assertTrue(profile.path("email").isNull());
        assertWallet(profile, owner);

        String key = UUID.randomUUID().toString();
        String mandate = mandate();
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/executions", owner.token(), key, mandate);
        JsonNode execution = body(created, 200);
        String id = execution.path("id").asText();
        assertEquals(owner.userId(), execution.path("ownerId").asText());
        assertEquals("CREATED", execution.path("status").asText());
        assertEquals(id, body(request(HttpMethod.POST, "/api/executions", owner.token(), key,
                mandate), 200).path("id").asText());

        assertEquals(id, body(request(HttpMethod.GET, "/api/executions/" + id, owner.token(), null, null), 200)
                .path("id").asText());
        assertTrue(containsId(body(request(HttpMethod.GET, "/api/executions", owner.token(), null, null), 200), id));
        assertTrue(containsId(body(request(HttpMethod.GET, "/api/executions/history", owner.token(), null, null), 200)
                .path("executions"), id));
        assertEquals("MANDATE_CONFIRMED", body(request(HttpMethod.GET, "/api/executions/" + id + "/events",
                owner.token(), null, null), 200).path("events").get(0).path("kind").asText());

        JsonNode run = body(request(HttpMethod.POST, "/api/executions/" + id + "/run",
                owner.token(), null, null), 200);
        assertEquals("FAILED", run.path("status").asText());
        JsonNode evidence = body(request(HttpMethod.GET, "/api/executions/" + id + "/evidence.json",
                owner.token(), null, null), 200);
        assertEquals(id, evidence.path("execution").path("id").asText());
        assertEquals(owner.userId(), evidence.path("execution").path("ownerId").asText());
        assertEquals("NOT_AVAILABLE", evidence.path("paymentStatus").asText());
        assertTrue(evidence.path("complete").asBoolean());
    }

    @Test
    void secondWalletCannotReadOrRunFirstWalletExecutionAndHasSeparateIdempotencyScope() throws Exception {
        WalletSession first = login();
        WalletSession second = login();
        assertNotEquals(first.userId(), second.userId());
        assertNotEquals(first.address(), second.address());

        String key = UUID.randomUUID().toString();
        String mandate = mandate();
        String firstId = body(request(HttpMethod.POST, "/api/executions", first.token(), key, mandate), 200)
                .path("id").asText();
        String secondId = body(request(HttpMethod.POST, "/api/executions", second.token(), key, mandate), 200)
                .path("id").asText();
        assertNotEquals(firstId, secondId);
        assertFalse(containsId(body(request(HttpMethod.GET, "/api/executions", second.token(), null, null), 200), firstId));
        assertFalse(containsId(body(request(HttpMethod.GET, "/api/executions/history", second.token(), null, null), 200)
                .path("executions"), firstId));
        JsonNode secondProfile = body(request(HttpMethod.GET, "/api/v1/users/me", second.token(), null, null), 200);
        assertEquals(second.userId(), secondProfile.path("userId").asText());
        assertWallet(secondProfile, second);
        assertNotEquals(first.walletId(), secondProfile.path("wallets").get(0).path("walletId").asText());

        for (String path : new String[] {"/api/executions/" + firstId,
                "/api/executions/" + firstId + "/events",
                "/api/executions/" + firstId + "/evidence.json"}) {
            assertError(request(HttpMethod.GET, path, second.token(), null, null), 404, "EXECUTION_NOT_FOUND");
        }
        assertError(request(HttpMethod.POST, "/api/executions/" + firstId + "/run",
                second.token(), null, null), 404, "EXECUTION_NOT_FOUND");
        assertEquals("CREATED", body(request(HttpMethod.GET, "/api/executions/" + firstId,
                first.token(), null, null), 200).path("status").asText());
    }

    @Test
    void protectedRoutesRejectMissingForgedAndAdminTokens() throws Exception {
        WalletSession wallet = login();
        String id = body(request(HttpMethod.POST, "/api/executions", wallet.token(),
                UUID.randomUUID().toString(), mandate()), 200).path("id").asText();
        JsonNode adminSignin = body(request(HttpMethod.POST, "/api/v1/admin/auth/signin", null, null,
                json.writeValueAsString(Map.of("email", "auth-admin@example.test", "password", "AdminPassword123!"))), 200);
        String admin = adminSignin.path("accessToken").asText();
        assertFalse(admin.isBlank());
        assertEquals(0, body(request(HttpMethod.GET, "/api/v1/users/me", admin, null, null), 200)
                .path("wallets").size());

        for (String path : new String[] {"/api/v1/users/me", "/api/executions/" + id,
                "/api/executions/" + id + "/events", "/api/executions/" + id + "/evidence.json"}) {
            assertError(request(HttpMethod.GET, path, null, null, null), 401, "UNAUTHORIZED");
            assertError(request(HttpMethod.GET, path, "not.a.valid.jwt", null, null), 401, "UNAUTHORIZED");
        }
        assertError(request(HttpMethod.POST, "/api/executions", null, UUID.randomUUID().toString(), mandate()),
                401, "UNAUTHORIZED");
        assertError(request(HttpMethod.POST, "/api/executions/" + id + "/run", null, null, null),
                401, "UNAUTHORIZED");
        assertError(request(HttpMethod.GET, "/api/executions/" + id, admin, null, null), 403, "FORBIDDEN");
        assertError(request(HttpMethod.POST, "/api/executions", admin, UUID.randomUUID().toString(), mandate()),
                403, "FORBIDDEN");
        assertError(request(HttpMethod.GET, "/api/v1/admin/does-not-exist", wallet.token(), null, null),
                403, "FORBIDDEN");
    }

    private WalletSession login() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        JsonNode challenge = body(request(HttpMethod.POST, "/api/v1/auth/wallet/nonce", null, null,
                json.writeValueAsString(Map.of("address", key.getAddress(), "chainId", 11155111))), 200);
        String message = challenge.path("message").asText();
        Sign.SignatureData signed = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), key.getEcKeyPair());
        byte[] signature = new byte[65];
        System.arraycopy(signed.getR(), 0, signature, 0, 32);
        System.arraycopy(signed.getS(), 0, signature, 32, 32);
        signature[64] = signed.getV()[0];
        JsonNode verified = body(request(HttpMethod.POST, "/api/v1/auth/wallet/verify", null, null,
                json.writeValueAsString(Map.of("message", message, "signature", Numeric.toHexString(signature)))), 200);
        String token = verified.path("accessToken").asText();
        String userId = verified.path("user").path("userId").asText();
        JsonNode wallet = verified.path("user").path("wallets").get(0);
        String walletId = wallet.path("walletId").asText();
        String address = wallet.path("address").asText();
        String walletType = wallet.path("walletType").asText();
        boolean primary = wallet.path("primary").asBoolean();
        assertFalse(token.isBlank());
        assertEquals(Keys.toChecksumAddress(key.getAddress()), address);
        assertEquals("EXTERNAL", walletType);
        assertTrue(primary);
        assertEquals("WALLET", verified.path("user").path("providers").get(0).asText());
        return new WalletSession(token, userId, walletId, address, walletType, primary);
    }

    private static void assertWallet(JsonNode profile, WalletSession session) {
        JsonNode wallets = profile.path("wallets");
        assertEquals(1, wallets.size());
        JsonNode wallet = wallets.get(0);
        assertEquals(session.walletId(), wallet.path("walletId").asText());
        assertEquals(session.address(), wallet.path("address").asText());
        assertEquals(session.walletType(), wallet.path("walletType").asText());
        assertEquals(session.primary(), wallet.path("primary").asBoolean());
    }

    private String mandate() throws Exception {
        return json.writeValueAsString(Map.of("confirmed", true, "mandate", Map.of(
                "goal", "Buy item-1 within budget", "itemId", "item-1", "maxTotal", "10.00",
                "currency", "TEST_USDC", "recipient", "merchant_good",
                "expiresAt", Instant.now().plusSeconds(3600).toString())));
    }

    private ResponseEntity<String> request(HttpMethod method, String path, String token, String idempotencyKey,
            String body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value());
        return json.readTree(response.getBody());
    }

    private void assertError(ResponseEntity<String> response, int status, String reason) throws Exception {
        assertEquals(reason, body(response, status).path("reasonCode").asText());
    }

    private static boolean containsId(JsonNode executions, String id) {
        for (JsonNode execution : executions) if (id.equals(execution.path("id").asText())) return true;
        return false;
    }
}
