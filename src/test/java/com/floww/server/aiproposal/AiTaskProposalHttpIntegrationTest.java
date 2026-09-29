package com.floww.server.aiproposal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** Real JWT, PostgreSQL Task APIs and local HTTP model responses. No live provider is used. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.auth.wallet.enabled=true",
        "floww.auth.wallet.origin=http://127.0.0.1:8080",
        "floww.auth.wallet.chain-ids=11155111",
        "floww.merchant.test-base-url="
})
class AiTaskProposalHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;
    private static final HttpServer PROVIDER;
    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.createContext("/v1/chat/completions", exchange -> {
                CALLS.incrementAndGet();
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                JsonNode payload = JSON.readTree(request.path("messages").get(1).path("content").asText());
                JsonNode eligible = payload.path("eligibleQuotes");
                assertEquals(1, eligible.size());
                assertEquals("pharmacy-a", eligible.get(0).path("merchantId").asText());
                String quoteId = eligible.get(0).path("quoteId").asText();
                CountDownLatch arrived = entered, hold = release;
                if (arrived != null && hold != null) {
                    arrived.countDown();
                    try { hold.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                String mode = MODE.get();
                String arguments = switch (mode) {
                    case "malformed" -> "{\"quoteId\":123}";
                    case "ineligible" -> "{\"quoteId\":\"unknown\"}";
                    default -> JSON.writeValueAsString(Map.of("quoteId", quoteId));
                };
                String response = JSON.writeValueAsString(Map.of("model", "qwen3-32b",
                        "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                                "tool_calls", List.of(Map.of("id", "fixture-call", "type", "function",
                                        "function", Map.of("name", "propose_purchase", "arguments", arguments)))))),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
                int status = "failure".equals(mode) ? 401 : 200;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("X-Neocloud-Generation-Id", "fixture-generation");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            PROVIDER.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry properties) {
        properties.add("floww.kiln.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1");
        properties.add("floww.kiln.api-key", () -> "local-fixture-only");
    }

    @AfterAll static void stop() { PROVIDER.stop(0); }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;

    private record Session(String token) { }

    @Test void selectedQuoteGoesThroughPolicyAndRepeatReusesAttempt() throws Exception {
        MODE.set("success");
        Session owner = login();
        String task = create(owner, "60000000");
        JsonNode quotes = body(call(HttpMethod.POST, path(task, "/quotes"), owner, null), 200).path("quotes");
        assertEquals(3, quotes.size());
        int before = CALLS.get();
        JsonNode first = body(call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null), 200);
        assertEquals(before + 1, CALLS.get());
        assertEquals("PROPOSED", first.path("proposal").path("status").asText());
        assertEquals("23500000", first.path("proposal").path("proposedQuote").path("totalBaseUnits").asText());
        assertEquals("OVER_BUDGET", reason(first, quoteId(quotes, "pharmacy-b")));
        assertEquals("RECIPIENT_NOT_PERMITTED", reason(first, quoteId(quotes, "pharmacy-c")));
        assertEquals("POLICY_ALLOWED", first.path("attempt").path("status").asText());
        assertEquals("ALLOW", first.path("attempt").path("policy").path("decision").asText());
        assertEquals("local_model_fixture", first.path("proposal").path("provenance")
                .path("modelEvidenceMode").asText());
        assertFalse(first.path("reusedAttempt").asBoolean());
        JsonNode second = body(call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null), 200);
        assertEquals(first.path("attempt").path("attemptId").asText(),
                second.path("attempt").path("attemptId").asText());
        assertTrue(second.path("reusedAttempt").asBoolean());
        JsonNode persisted = body(call(HttpMethod.GET, path(task, ""), owner, null), 200);
        assertEquals("AWAITING_APPROVAL", persisted.path("status").asText());
        assertEquals("DRAFT", persisted.path("mandate").path("status").asText());
        assertEquals(1, persisted.path("attempts").size());
        assertTrue(persisted.path("attempts").get(0).path("approval").isNull());
        assertTrue(persisted.path("attempts").get(0).path("order").isNull());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM merchant_orders WHERE task_id=?::uuid",
                Integer.class, task));
        JsonNode events = body(call(HttpMethod.GET, path(task, "/events?limit=100"), owner, null), 200)
                .path("events");
        JsonNode evidence = aiEvent(events);
        assertEquals("PROPOSED", evidence.path("payload").path("status").asText());
        assertEquals("local_model_fixture", evidence.path("payload").path("modelEvidenceMode").asText());
        assertEquals("tool_calls", evidence.path("payload").path("finishReason").asText());
        assertEquals("fixture-call", evidence.path("payload").path("toolCallId").asText());
        assertEquals(task, evidence.path("payload").path("taskRef").asText());
        assertEquals("1", evidence.path("payload").path("mandateRevision").asText());
        assertEquals(3, evidence.path("payload").path("findings").size());
        assertFalse(evidence.path("payload").has("rawArguments"));
        assertEquals("no-store", call(HttpMethod.POST, path(task, "/ai-proposal"), null, null)
                .getHeaders().getFirst("Cache-Control"));
    }

    @Test void ownerAndBodyAreEnforcedAndFailuresCreateNoAttempts() throws Exception {
        MODE.set("success");
        Session owner = login(), other = login();
        String task = create(owner, "60000000");
        assertEquals(404, call(HttpMethod.POST, path(task, "/ai-proposal"), other, null).getStatusCode().value());
        assertEquals(400, call(HttpMethod.POST, path(task, "/ai-proposal"), owner, "{}").getStatusCode().value());
        for (String mode : List.of("malformed", "ineligible", "failure")) {
            MODE.set(mode);
            JsonNode result = body(call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null), 200);
            assertEquals(mode.equals("ineligible") ? "REJECTED" : "MODEL_FAILURE",
                    result.path("proposal").path("status").asText());
            assertTrue(result.path("attempt").isNull());
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM execution_attempts WHERE task_id=?::uuid",
                Integer.class, task));
        JsonNode events = body(call(HttpMethod.GET, path(task, "/events?limit=100"), owner, null), 200)
                .path("events");
        assertEquals("MODEL_FAILURE", aiEvent(events).path("payload").path("status").asText());
        MODE.set("success");
        int before = CALLS.get();
        String lowBudget = create(owner, "1000000");
        JsonNode noCandidate = body(call(HttpMethod.POST, path(lowBudget, "/ai-proposal"), owner, null), 200);
        assertEquals("NO_CANDIDATE", noCandidate.path("proposal").path("status").asText());
        assertEquals(before, CALLS.get());
    }

    @Test void chunkedNonemptyBodyIsRejectedWithoutModelCall() throws Exception {
        MODE.set("success");
        Session owner = login();
        String task = create(owner, "60000000");
        int before = CALLS.get();
        byte[] input = new byte[256 * 1024];
        java.util.Arrays.fill(input, (byte) 'x');
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + path(task, "/ai-proposal")))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(input))).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("INVALID_INPUT", json.readTree(response.body()).path("reasonCode").asText());
        assertEquals(before, CALLS.get());
    }

    @Test void revisionDuringInferenceDoesNotBlockAndCannotPersistStaleAttempt() throws Exception {
        MODE.set("success");
        Session owner = login();
        String task = create(owner, "60000000");
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try {
            CompletableFuture<ResponseEntity<String>> pending = CompletableFuture.supplyAsync(
                    () -> call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String revision = json.writeValueAsString(Map.of("baseVersion", 1,
                    "goal", "Revised purchase", "itemId", "acetaminophen-500mg-10",
                    "maxAmountBaseUnits", "50000000", "expiresAt",
                    Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()));
            // This HTTP update must finish while the model response is still held.
            JsonNode revised = body(call(HttpMethod.POST, path(task, "/mandate/revisions"), owner, revision), 200);
            assertEquals(2, revised.path("mandate").path("version").asInt());
            release.countDown();
            JsonNode result = body(pending.get(8, TimeUnit.SECONDS), 200);
            assertEquals("SNAPSHOT_STALE", result.path("proposal").path("reason").asText());
            assertTrue(result.path("attempt").isNull());
            JsonNode events = body(call(HttpMethod.GET, path(task, "/events?limit=100"), owner, null), 200)
                    .path("events");
            assertEquals("1", aiEvent(events).path("payload").path("mandateRevision").asText());
            assertEquals(result.path("proposal").path("mandateRef").asText(),
                    aiEvent(events).path("payload").path("mandateRef").asText());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM execution_attempts WHERE task_id=?::uuid",
                    Integer.class, task));
        } finally { release.countDown(); entered = null; release = null; }
    }

    @Test void quoteExpiryDuringInferenceCannotPersistAttempt() throws Exception {
        MODE.set("success");
        Session owner = login();
        String task = create(owner, "60000000");
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try {
            CompletableFuture<ResponseEntity<String>> pending = CompletableFuture.supplyAsync(
                    () -> call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, db.update("UPDATE merchant_quotes SET expires_at=now()-interval '1 second' "
                    + "WHERE task_id=?::uuid AND merchant_id='pharmacy-a'", task));
            release.countDown();
            JsonNode result = body(pending.get(8, TimeUnit.SECONDS), 200);
            assertEquals("SNAPSHOT_STALE", result.path("proposal").path("reason").asText());
            assertTrue(result.path("attempt").isNull());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM execution_attempts WHERE task_id=?::uuid",
                    Integer.class, task));
        } finally { release.countDown(); entered = null; release = null; }
    }

    @Test void mandateExpiryDuringInferenceCannotPersistAttempt() throws Exception {
        MODE.set("success");
        Session owner = login();
        String task = create(owner, "60000000");
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try {
            CompletableFuture<ResponseEntity<String>> pending = CompletableFuture.supplyAsync(
                    () -> call(HttpMethod.POST, path(task, "/ai-proposal"), owner, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, db.update("UPDATE mandate_versions SET expires_at=now()-interval '1 second' "
                    + "WHERE task_id=?::uuid AND version=1", task));
            release.countDown();
            JsonNode result = body(pending.get(8, TimeUnit.SECONDS), 200);
            assertEquals("SNAPSHOT_STALE", result.path("proposal").path("reason").asText());
            assertTrue(result.path("attempt").isNull());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM execution_attempts WHERE task_id=?::uuid",
                    Integer.class, task));
        } finally { release.countDown(); entered = null; release = null; }
    }

    private String reason(JsonNode result, String wantedQuoteId) {
        JsonNode findings = result.path("proposal").path("findings");
        for (JsonNode finding : findings) {
            String quoteId = finding.path("quoteId").asText();
            if (quoteId.equals(wantedQuoteId)) return finding.path("reasons").get(0).asText();
        }
        throw new AssertionError("missing finding for " + wantedQuoteId);
    }

    private static String quoteId(JsonNode quotes, String merchant) {
        for (JsonNode quote : quotes) {
            if (merchant.equals(quote.path("merchantId").asText())) return quote.path("quoteId").asText();
        }
        throw new AssertionError("missing quote for " + merchant);
    }

    private static JsonNode aiEvent(JsonNode events) {
        JsonNode found = null;
        for (JsonNode event : events) {
            if ("AI_PROPOSAL_RESULT".equals(event.path("kind").asText())) found = event;
        }
        if (found == null) throw new AssertionError("missing AI proposal event");
        return found;
    }

    private String create(Session owner, String budget) throws Exception {
        String request = json.writeValueAsString(Map.of("goal", "Buy medicine", "itemId",
                "acetaminophen-500mg-10", "maxAmountBaseUnits", budget, "expiresAt",
                Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return body(http.exchange("/api/v1/tasks", HttpMethod.POST, new HttpEntity<>(request, headers),
                String.class), 201).path("taskId").asText();
    }

    private Session login() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        JsonNode challenge = body(call(HttpMethod.POST, "/api/v1/auth/wallet/nonce", null,
                json.writeValueAsString(Map.of("address", key.getAddress(), "chainId", 11155111))), 200);
        Sign.SignatureData signed = Sign.signPrefixedMessage(challenge.path("message").asText()
                .getBytes(StandardCharsets.UTF_8), key.getEcKeyPair());
        byte[] signature = new byte[65];
        System.arraycopy(signed.getR(), 0, signature, 0, 32);
        System.arraycopy(signed.getS(), 0, signature, 32, 32);
        signature[64] = signed.getV()[0];
        JsonNode verified = body(call(HttpMethod.POST, "/api/v1/auth/wallet/verify", null,
                json.writeValueAsString(Map.of("message", challenge.path("message").asText(),
                        "signature", Numeric.toHexString(signature)))), 200);
        return new Session(verified.path("accessToken").asText());
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Session owner, String request) {
        HttpHeaders headers = new HttpHeaders();
        if (owner != null) headers.setBearerAuth(owner.token());
        if (request != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(request, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return json.readTree(response.getBody());
    }

    private static String path(String task, String suffix) { return "/api/v1/tasks/" + task + suffix; }
}
