package com.floww.server.auth.wallet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.auth.JwtProvider;
import com.floww.server.auth.TokenAudience;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "floww.auth.alice-token=ci-only-alice-token-0001",
        "floww.auth.bob-token=ci-only-bob-token-00002",
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.auth.wallet.enabled=true", "floww.auth.wallet.example-enabled=true",
        "floww.auth.wallet.origin=http://127.0.0.1:8080", "floww.auth.wallet.chain-ids=11155111"
})
class WalletSigninHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired JwtProvider jwt;
    @Autowired WalletSigninRepository repository;
    @Autowired TransactionTemplate transactions;
    @LocalServerPort int port;

    private static Credentials key() throws Exception { return Credentials.create(Keys.createEcKeyPair()); }
    private static String address(Credentials key) { return key.getAddress(); }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return http.postForEntity(path, body, JsonNode.class);
    }
    private JsonNode nonce(Credentials key) {
        ResponseEntity<JsonNode> response = post("/api/v1/auth/wallet/nonce",
                Map.of("address", address(key), "chainId", 11155111));
        assertEquals(200, response.getStatusCode().value(), response.toString());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        return response.getBody();
    }
    private static String signature(Credentials key, String message) {
        Sign.SignatureData data = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), key.getEcKeyPair());
        byte[] out = new byte[65];
        System.arraycopy(data.getR(), 0, out, 0, 32);
        System.arraycopy(data.getS(), 0, out, 32, 32);
        out[64] = data.getV()[0];
        return Numeric.toHexString(out);
    }
    private ResponseEntity<JsonNode> verify(String message, String sig) {
        return post("/api/v1/auth/wallet/verify", Map.of("message", message, "signature", sig));
    }
    private long walletUsers() {
        return db.queryForObject("SELECT count(*) FROM users WHERE provider='WALLET'", Long.class);
    }
    private static void code(ResponseEntity<JsonNode> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value(), response.toString());
        assertEquals(code, response.getBody().path("reasonCode").asText());
        assertFalse(response.getBody().has("accessToken"));
    }

    @Test
    void firstAndReturningSigninIssueCommonClientJwtWithRealWallet() throws Exception {
        Credentials key = key();
        JsonNode first = nonce(key);
        assertEquals(48, first.path("nonce").asText().length());
        assertEquals(Instant.parse(first.path("expiresAt").asText()),
                Instant.parse(first.path("message").asText().split("Expiration Time: ")[1]));
        String message = first.path("message").asText();
        assertTrue(message.startsWith("http://127.0.0.1:8080 wants you to sign in with your Ethereum account:\n"));
        ResponseEntity<JsonNode> signed = verify(message, signature(key, message));
        assertEquals(200, signed.getStatusCode().value(), signed.toString());
        JsonNode body = signed.getBody();
        assertTrue(body.path("isNewUser").asBoolean());
        assertEquals("Bearer", body.path("tokenType").asText());
        assertEquals(1800, body.path("expiresIn").asInt());
        assertFalse(body.has("refreshToken"));
        assertTrue(body.path("user").path("email").isNull());
        assertEquals("USER", body.path("user").path("role").asText());
        assertEquals("WALLET", body.path("user").path("providers").get(0).asText());
        assertEquals(Keys.toChecksumAddress(address(key)),
                body.path("user").path("wallets").get(0).path("address").asText());
        assertEquals("EXTERNAL", body.path("user").path("wallets").get(0).path("walletType").asText());
        assertTrue(body.path("user").path("wallets").get(0).path("primary").asBoolean());
        JwtProvider.VerifiedToken token = jwt.verify(body.path("accessToken").asText()).orElseThrow();
        assertEquals(TokenAudience.CLIENT, token.audience());
        assertEquals(body.path("user").path("userId").asText(), token.userId().toString());

        JsonNode next = nonce(key);
        ResponseEntity<JsonNode> again = verify(next.path("message").asText(),
                signature(key, next.path("message").asText()));
        assertEquals(200, again.getStatusCode().value());
        assertFalse(again.getBody().path("isNewUser").asBoolean());
        assertEquals(token.userId().toString(), again.getBody().path("user").path("userId").asText());
    }

    @Test
    void invalidInputsWrongSignerModificationAndExpiryHaveNoUserSideEffects() throws Exception {
        long before = walletUsers();
        Credentials key = key();
        Credentials other = key();
        code(post("/api/v1/auth/wallet/nonce", Map.of("address", address(key), "chainId", -1)),
                400, "INVALID_INPUT");
        code(post("/api/v1/auth/wallet/nonce", Map.of("address", address(key), "chainId", 1)),
                400, "CHAIN_NOT_SUPPORTED");
        code(post("/api/v1/auth/wallet/nonce", Map.of("address", "0x123", "chainId", 11155111)),
                400, "INVALID_INPUT");
        code(post("/api/v1/auth/wallet/nonce", Map.of(
                "address", "0x52908400098527886E0F7030069857D2E4169Ee7", "chainId", 11155111)),
                400, "INVALID_INPUT");
        code(post("/api/v1/auth/wallet/nonce", Map.of("address", address(key), "chainId", 11155111,
                "role", "ADMIN")), 400, "INVALID_INPUT");
        JsonNode challenge = nonce(key);
        String message = challenge.path("message").asText();
        ResponseEntity<JsonNode> wrongSigner = verify(message, signature(other, message));
        code(wrongSigner, 401, "SIGNATURE_INVALID");
        assertTrue(wrongSigner.getHeaders().getCacheControl().contains("no-store"));
        code(verify(message.replace("Chain ID: 11155111", "Chain ID: 1"), signature(key, message)),
                401, "MESSAGE_MISMATCH");
        code(verify(message, "0x" + "00".repeat(65)), 401, "SIGNATURE_INVALID");
        code(verify(message, "0x1234"), 400, "INVALID_INPUT");
        assertEquals(before, walletUsers());
        // Invalid signatures do not burn the challenge; the owner may retry.
        assertEquals(200, verify(message, signature(key, message)).getStatusCode().value());
        code(verify(message, signature(key, message)), 401, "NONCE_INVALID");

        Credentials expiring = key();
        JsonNode expired = nonce(expiring);
        db.update("UPDATE wallet_login_challenges SET expires_at = clock_timestamp() - interval '1 second' WHERE nonce = ?",
                expired.path("nonce").asText());
        long afterValid = walletUsers();
        code(verify(expired.path("message").asText(), signature(expiring, expired.path("message").asText())),
                401, "NONCE_EXPIRED");
        assertEquals(afterValid, walletUsers());
    }

    @Test
    void suspendedUserGetsNoTokenAndChallengeRollsBack() throws Exception {
        Credentials key = key();
        JsonNode first = nonce(key);
        JsonNode signed = verify(first.path("message").asText(), signature(key, first.path("message").asText())).getBody();
        db.update("UPDATE users SET status='SUSPENDED' WHERE id = ?",
                java.util.UUID.fromString(signed.path("user").path("userId").asText()));
        JsonNode second = nonce(key);
        String message = second.path("message").asText();
        code(verify(message, signature(key, message)), 403, "USER_SUSPENDED");
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM wallet_login_challenges "
                + "WHERE nonce = ? AND consumed_at IS NOT NULL", Long.class, second.path("nonce").asText()));
    }

    @Test
    void concurrentReplayOnlyOneResponseSucceeds() throws Exception {
        Credentials key = key();
        JsonNode challenge = nonce(key);
        String message = challenge.path("message").asText();
        String sig = signature(key, message);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> { start.await(); return verify(message, sig).getStatusCode().value(); };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertEquals(java.util.List.of(200, 401), Arrays.stream(new int[]{a.get(), b.get()}).sorted().boxed().toList());
        }
    }

    @Test
    void finalDatabaseConsumeUsesLiveClockAfterTransactionStart() throws Exception {
        Credentials key = key();
        JsonNode challenge = nonce(key);
        Boolean consumed = transactions.execute(tx -> {
            db.queryForObject("SELECT now()", java.sql.Timestamp.class); // establish transaction timestamp
            db.update("UPDATE wallet_login_challenges SET expires_at = clock_timestamp() + interval '1 second' "
                    + "WHERE nonce = ?", challenge.path("nonce").asText());
            try { Thread.sleep(1300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return repository.consume(challenge.path("nonce").asText());
        });
        assertFalse(consumed);
    }

    @Test
    void expiryDuringAddressLockWaitRollsBackNewIdentity() throws Exception {
        Credentials key = key();
        JsonNode challenge = nonce(key);
        String message = challenge.path("message").asText();
        String sig = signature(key, message);
        long before = walletUsers();
        db.update("UPDATE wallet_login_challenges SET expires_at = clock_timestamp() + interval '1 second' "
                + "WHERE nonce = ?", challenge.path("nonce").asText());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transactions.execute(tx -> {
                repository.lockAddress(address(key).toLowerCase(java.util.Locale.ROOT));
                locked.countDown();
                try { release.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new RuntimeException(e);
                }
                return null;
            }));
            assertTrue(locked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var request = pool.submit(() -> verify(message, sig));
            Thread.sleep(1350);
            assertFalse(request.isDone(), "verification should wait on the identity lock");
            release.countDown();
            holder.get();
            code(request.get(), 401, "NONCE_INVALID");
        } finally {
            release.countDown();
        }
        assertEquals(before, walletUsers());
    }

    @Test
    void exactPublicRoutesAndExampleAreAccessibleButOtherRoutesRemainProtected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> forbidden = http.exchange("/api/v1/auth/other", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), JsonNode.class);
        code(forbidden, 401, "UNAUTHORIZED");
        ResponseEntity<String> example = http.getForEntity("/wallet-signin-example/", String.class);
        assertEquals(200, example.getStatusCode().value());
        assertTrue(example.getBody().contains("MetaMask로 로그인"));
        assertEquals(200, http.getForEntity("/wallet-signin-example/style.css", String.class).getStatusCode().value());
        assertEquals(401, http.getForEntity("/api/tasks", String.class).getStatusCode().value());
    }

    @Test
    void forwardedHostCannotChangeSignedOrigin() throws Exception {
        Credentials key = key();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-Host", "attacker.example");
        headers.set("X-Forwarded-Proto", "https");
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/auth/wallet/nonce", HttpMethod.POST,
                new HttpEntity<>(Map.of("address", address(key), "chainId", 11155111), headers), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().path("message").asText().startsWith(
                "http://127.0.0.1:8080 wants you to sign in"));
        assertFalse(response.getBody().path("message").asText().contains("attacker.example"));
    }

    @Test
    void globalChallengeAdmissionCapRejectsFurtherIssueWithoutPersistentChanges() {
        transactions.execute(tx -> {
            db.update("DELETE FROM wallet_login_challenges");
            db.update("INSERT INTO wallet_login_challenges(nonce,address,chain_id,message,expires_at) "
                    + "SELECT lpad(g::text,48,'0'), '0x' || repeat('1',40), 11155111, 'fixture', "
                    + "clock_timestamp() + interval '5 minutes' FROM generate_series(1,10000) g");
            ApiException rejection = assertThrows(ApiException.class,
                    () -> repository.insertChallenge(new WalletSigninRepository.Challenge(
                            "f".repeat(48), "0x" + "2".repeat(40), 11155111, "fixture",
                            Instant.now().plusSeconds(300), false)));
            assertEquals(ErrorCode.TOO_MANY_REQUESTS, rejection.errorCode());
            tx.setRollbackOnly();
            return null;
        });
    }

    @Test
    void rawHttpRejectsChunkedOversizeDuplicateKeysAndInvalidUtf8() throws Exception {
        long before = walletUsers();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String path = "http://127.0.0.1:" + port + "/api/v1/auth/wallet/nonce";
            HttpRequest oversized = HttpRequest.newBuilder(URI.create(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(
                            () -> new java.io.ByteArrayInputStream(("{\"x\":\"" + "x".repeat(9000) + "\"}")
                                    .getBytes(StandardCharsets.UTF_8)))).build();
            HttpResponse<String> large = client.send(oversized, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, large.statusCode());
            assertEquals("REQUEST_TOO_LARGE", json.readTree(large.body()).path("reasonCode").asText());
            assertTrue(large.headers().firstValue("Cache-Control").orElse("").contains("no-store"));

            HttpRequest duplicate = HttpRequest.newBuilder(URI.create(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"a\",\"address\":\"b\",\"chainId\":11155111}"))
                    .build();
            HttpResponse<String> dup = client.send(duplicate, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, dup.statusCode());
            assertEquals("MALFORMED_JSON", json.readTree(dup.body()).path("reasonCode").asText());

            HttpRequest badUtf8 = HttpRequest.newBuilder(URI.create(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{'{','"','x','"',':','"',(byte) 0xc3,'"','}'}))
                    .build();
            HttpResponse<String> invalid = client.send(badUtf8, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            assertEquals("MALFORMED_JSON", json.readTree(invalid.body()).path("reasonCode").asText());
        }
        assertEquals(before, walletUsers());
    }
}
