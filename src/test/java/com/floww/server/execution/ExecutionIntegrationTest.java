package com.floww.server.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"floww.auth.alice-token=ci-only-alice-token-0001",
                "floww.auth.bob-token=ci-only-bob-token-00002",
                "floww.kiln.api-key=fixture-only-provider-key"})
class ExecutionIntegrationTest {
    private record Fake(int status, String body, long delayMillis) {
        Fake(int status, String body) { this(status, body, 0); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LinkedBlockingQueue<Fake> REPLIES = new LinkedBlockingQueue<>();
    private static final List<JsonNode> REQUESTS = new ArrayList<>();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final HttpServer PROVIDER = provider();
    private static final HttpServer MERCHANT = merchant();
    private static volatile String quoteCost = "9.50";
    private static volatile String quoteRecipient = "merchant_good";
    private static volatile String quoteItem = "item-1";
    private static volatile Instant quoteExpiry;
    @Autowired TestRestTemplate http;
    @Autowired ExecutionStore store;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource static void urls(DynamicPropertyRegistry registry) {
        registry.add("floww.kiln.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1");
        registry.add("floww.merchant.test-base-url", () -> "http://127.0.0.1:" + MERCHANT.getAddress().getPort());
    }
    private static HttpServer provider() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/chat/completions", x -> {
                COUNT.incrementAndGet();
                synchronized (REQUESTS) { REQUESTS.add(JSON.readTree(x.getRequestBody().readAllBytes())); }
                Fake f;
                try { f = REPLIES.poll(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); f = null; }
                if (f == null) f = new Fake(500, "no fixture");
                if (f.delayMillis() > 0) {
                    try { Thread.sleep(f.delayMillis()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                x.getResponseHeaders().add("X-Neocloud-Generation-Id", "fixture-generation-1");
                byte[] body = f.body().getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(f.status(), body.length);
                try (var out = x.getResponseBody()) { out.write(body); }
            });
            s.start(); return s;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static HttpServer merchant() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/offers", x -> reply(x, "{\"offers\":[{\"offerId\":\"offer-1\",\"itemId\":\"item-1\"}]}"));
            s.createContext("/quotes", x -> reply(x, "{\"quoteId\":\"quote-1\",\"offerId\":\"offer-1\",\"itemId\":\""
                    + quoteItem + "\",\"totalCost\":\"" + quoteCost + "\",\"currency\":\"TEST_USDC\",\"recipient\":\""
                    + quoteRecipient + "\",\"expiresAt\":\"" + quoteExpiry + "\"}"));
            s.start(); return s;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange x, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(200, body.length);
        try (var out = x.getResponseBody()) { out.write(body); }
    }
    @AfterAll static void stop() { PROVIDER.stop(0); MERCHANT.stop(0); }
    @BeforeEach void clear() {
        REPLIES.clear(); synchronized (REQUESTS) { REQUESTS.clear(); }
        COUNT.set(0); quoteCost = "9.50"; quoteRecipient = "merchant_good";
        quoteItem = "item-1"; quoteExpiry = Instant.now().plus(5, ChronoUnit.MINUTES);
    }

    @Test void boundedRoundTripPersistsEvidenceAndOwnerIsolation() throws Exception {
        UUID id = create("alice", "10.00", "merchant_good");
        queueHappy();
        assertEquals("REVIEWED", run(id).path("status").asText());
        assertEquals(4, COUNT.get());
        synchronized (REQUESTS) {
            for (int i = 1; i < 4; i++) {
                JsonNode messages = REQUESTS.get(i).path("messages");
                assertEquals("assistant", messages.get(messages.size() - 2).path("role").asText());
                assertEquals("call-" + i, messages.get(messages.size() - 1).path("tool_call_id").asText());
                assertEquals("call-" + i, messages.get(messages.size() - 2).path("tool_calls").get(0).path("id").asText());
            }
            assertEquals(768, REQUESTS.get(0).path("max_tokens").asInt());
            assertEquals("auto", REQUESTS.get(0).path("tool_choice").asText());
            assertFalse(REQUESTS.get(3).has("tools"));
        }
        JsonNode export = evidence(id);
        assertEquals("floww-evidence-2", export.path("format").asText());
        assertTrue(export.path("complete").asBoolean());
        assertEquals("local_test_merchant", export.path("evidenceMode").asText());
        assertEquals("local_model_fixture", export.path("modelEvidenceMode").asText());
        assertEquals("complete", export.path("modelUsage").path("status").asText());
        assertEquals(4, export.path("modelUsage").path("attempts").asInt());
        assertEquals("NOT_AVAILABLE", export.path("paymentStatus").asText());
        assertEquals("POLICY_PRECHECK_PASSED", lastKind(export));
        for (JsonNode e : export.path("events").path("events")) {
            assertEquals(2, e.path("schemaVersion").asInt());
            assertEquals(id.toString(), e.path("correlationId").asText());
            assertTrue(e.has("createdAt"));
            if (List.of("MODEL_RESPONSE", "TOOL_REQUEST").contains(e.path("kind").asText())) {
                assertEquals("local_model_fixture", e.path("modelEvidenceMode").asText());
                assertEquals("local_model_fixture", e.path("source").asText());
            }
        }
        assertEquals(401, request(null, HttpMethod.GET, "/api/executions/" + id + "/evidence.json", null).getStatusCode().value());
        assertEquals(404, request("bob", HttpMethod.GET, "/api/executions/" + id + "/evidence.json", null).getStatusCode().value());
        assertEquals(404, request("bob", HttpMethod.GET, "/api/executions/" + id, null).getStatusCode().value());
        JsonNode page = json(request("alice", HttpMethod.GET, "/api/executions/" + id + "/events?limit=2", null));
        assertTrue(page.path("hasMore").asBoolean());
        assertEquals(2, page.path("events").size());
        JsonNode history = json(request("alice", HttpMethod.GET, "/api/executions/history?limit=1", null));
        assertTrue(history.path("executions").size() == 1);
    }

    @Test void rejectsBudgetRecipientItemAndExpiredQuote() throws Exception {
        for (String scenario : List.of("budget", "recipient", "item", "quote")) {
            UUID id = create("alice", "10.00", "merchant_good");
            switch (scenario) {
                case "budget" -> quoteCost = "10.01";
                case "recipient" -> quoteRecipient = "merchant_other";
                case "item" -> quoteItem = "item-other";
                default -> quoteExpiry = Instant.now().minusSeconds(1);
            }
            REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
            REPLIES.add(new Fake(200, model("call-2", "get_quote", "{\"offerId\":\"offer-1\"}")));
            assertEquals("REJECTED", run(id).path("status").asText(), scenario);
            assertEquals(switch (scenario) { case "budget" -> "BUDGET_EXCEEDED";
                case "recipient" -> "RECIPIENT_NOT_ALLOWED"; case "item" -> "ITEM_NOT_ALLOWED";
                default -> "QUOTE_STALE"; }, lastCode(id));
            REPLIES.clear(); quoteCost = "9.50"; quoteRecipient = "merchant_good";
            quoteItem = "item-1"; quoteExpiry = Instant.now().plusSeconds(300);
        }
    }

    @Test void rejectsMalformedUnknownDuplicateAndTruncatedOutputs() throws Exception {
        UUID unknown = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "send_payment", "{}")));
        assertEquals("REJECTED", run(unknown).path("status").asText());
        assertEquals("TOOL_NOT_ALLOWED", lastCode(unknown));
        UUID malformed = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{bad")));
        assertEquals("REJECTED", run(malformed).path("status").asText());
        assertEquals("INVALID_TOOL_ARGUMENTS", lastCode(malformed));
        UUID duplicate = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
        REPLIES.add(new Fake(200, model("call-1", "get_quote", "{\"offerId\":\"offer-1\"}")));
        assertEquals("REJECTED", run(duplicate).path("status").asText());
        assertEquals("DUPLICATE_TOOL_CALL_ID", lastCode(duplicate));
        UUID truncated = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")
                .replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\"")));
        assertEquals("FAILED", run(truncated).path("status").asText());
        assertEquals("MODEL_OUTPUT_TRUNCATED", lastCode(truncated));
    }

    @Test void rejectsEarlyStopAndUnknownQuote() throws Exception {
        UUID early = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, stopModel()));
        assertEquals("REJECTED", run(early).path("status").asText());
        assertEquals("EARLY_MODEL_STOP", lastCode(early));
        UUID wrong = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
        REPLIES.add(new Fake(200, model("call-2", "get_quote", "{\"offerId\":\"offer-1\"}")));
        REPLIES.add(new Fake(200, model("call-3", "propose_purchase", "{\"quoteId\":\"unknown\"}")));
        assertEquals("REJECTED", run(wrong).path("status").asText());
        assertEquals("UNKNOWN_QUOTE_ID", lastCode(wrong));
    }

    @Test void blocksLoopCapProviderAuthAndExpiredMandate() throws Exception {
        UUID loop = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
        REPLIES.add(new Fake(200, model("call-2", "get_quote", "{\"offerId\":\"offer-1\"}")));
        REPLIES.add(new Fake(200, model("call-3", "propose_purchase", "{\"quoteId\":\"quote-1\"}")));
        REPLIES.add(new Fake(200, model("call-4", "propose_purchase", "{\"quoteId\":\"quote-1\"}")));
        assertEquals("REJECTED", run(loop).path("status").asText());
        assertEquals("TOOL_LOOP_LIMIT", lastCode(loop));
        UUID auth = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(401, "denied"));
        assertEquals("FAILED", run(auth).path("status").asText());
        assertEquals("PROVIDER_HTTP_401", lastCode(auth));
        assertEquals(400, createRequest("alice", UUID.randomUUID().toString(),
                mandate("10.00", "merchant_good").replaceAll("\\d{4}-\\d{2}-\\d{2}T[^\"]+",
                        Instant.now().minusSeconds(1).toString())).getStatusCode().value());
    }

    @Test void idempotencyAndStrictMandate() throws Exception {
        String key = UUID.randomUUID().toString(); String body = mandate("10.00", "merchant_good");
        JsonNode first = json(createRequest("alice", key, body));
        assertEquals(first.path("id").asText(), json(createRequest("alice", key, body)).path("id").asText());
        assertEquals(409, createRequest("alice", key, mandate("11.00", "merchant_good")).getStatusCode().value());
        assertEquals(400, createRequest("alice", UUID.randomUUID().toString(),
                body.replace("\"itemId\":\"item-1\"", "\"itemId\":\"item-1\",\"ownerId\":\"bob\""))
                .getStatusCode().value());
    }

    @Test void finalCallRechecksQuoteAndMandateExpiry() throws Exception {
        UUID quoteId = create("alice", "10.00", "merchant_good");
        quoteExpiry = Instant.now().plusSeconds(3);
        queueHappyWithFinalDelay(4000);
        assertEquals("REJECTED", run(quoteId).path("status").asText());
        assertEquals(4, COUNT.get(), "quote must expire during final model call");
        assertEquals("QUOTE_STALE", lastCode(quoteId));
        REPLIES.clear();
        COUNT.set(0);

        Instant mandateExpiry = Instant.now().plusSeconds(5);
        String body = mandate("10.00", "merchant_good")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[^\"]+", mandateExpiry.toString());
        UUID mandateId = UUID.fromString(json(createRequest("alice", UUID.randomUUID().toString(), body))
                .path("id").asText());
        quoteExpiry = Instant.now().plusSeconds(300);
        queueHappyWithFinalDelay(6000);
        assertEquals("REJECTED", run(mandateId).path("status").asText());
        assertEquals(4, COUNT.get(), "mandate must expire during final model call");
        assertEquals("MANDATE_EXPIRED", lastCode(mandateId));
    }

    @Test void evidenceCompletenessRequiresTerminalAndFullPage() throws Exception {
        UUID created = create("alice", "10.00", "merchant_good");
        assertFalse(evidence(created).path("complete").asBoolean());
        assertTrue(evidence(created).path("pageComplete").asBoolean());
        store.claim("alice", created, "local_test_merchant");
        assertFalse(evidence(created).path("complete").asBoolean());

        UUID terminal = create("alice", "10.00", "merchant_good");
        queueHappy();
        assertEquals("REVIEWED", run(terminal).path("status").asText());
        JsonNode partial = json(request("alice", HttpMethod.GET,
                "/api/executions/" + terminal + "/evidence.json?limit=2", null));
        assertFalse(partial.path("complete").asBoolean());
        assertFalse(partial.path("pageComplete").asBoolean());
        JsonNode later = json(request("alice", HttpMethod.GET,
                "/api/executions/" + terminal + "/evidence.json?after="
                + partial.path("nextCursor").asLong(), null));
        assertFalse(later.path("complete").asBoolean());
        assertTrue(later.path("pageComplete").asBoolean());
        assertTrue(evidence(terminal).path("complete").asBoolean());
    }

    @Test void failedAndRetriedProviderAttemptsKeepUsageProvenance() throws Exception {
        UUID truncated = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")
                .replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\"")));
        assertEquals("FAILED", run(truncated).path("status").asText());
        JsonNode truncatedExport = evidence(truncated);
        assertEquals("complete", truncatedExport.path("modelUsage").path("status").asText());
        assertEquals(1, truncatedExport.path("modelUsage").path("attempts").asInt());
        assertEquals(17, truncatedExport.path("modelUsage").path("totals").path("totalTokens").asInt());
        REPLIES.clear();

        UUID retry = create("alice", "10.00", "merchant_good");
        REPLIES.add(new Fake(503, "temporary unavailable"));
        queueHappy();
        assertEquals("REVIEWED", run(retry).path("status").asText());
        JsonNode retryExport = evidence(retry);
        assertEquals("incomplete", retryExport.path("modelUsage").path("status").asText());
        assertEquals(5, retryExport.path("modelUsage").path("attempts").asInt());
        assertFalse(retryExport.path("modelUsage").has("totals"));
        JsonNode events = retryExport.path("events").path("events");
        boolean unknown = false;
        for (JsonNode event : events) if (event.path("kind").asText().equals("MODEL_RESPONSE")
                && event.path("payload").path("usageStatus").asText().equals("unknown")) unknown = true;
        assertTrue(unknown);
    }

    @Test void historicalModelEventsRemainUnknownProvenance() throws Exception {
        UUID id = create("alice", "10.00", "merchant_good");
        db.update("INSERT INTO execution_events(execution_id,kind,payload) VALUES (?, 'MODEL_RESPONSE', ?::jsonb)",
                id, "{\"modelId\":\"qwen3-32b\",\"usageStatus\":\"reported\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        JsonNode export = evidence(id);
        assertEquals("unknown", export.path("modelEvidenceMode").asText());
        assertEquals("complete", export.path("modelUsage").path("status").asText());
        assertTrue(export.path("events").path("events").get(1).path("modelEvidenceMode").isNull());
    }

    private void queueHappyWithFinalDelay(long delayMillis) throws Exception {
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
        REPLIES.add(new Fake(200, model("call-2", "get_quote", "{\"offerId\":\"offer-1\"}")));
        REPLIES.add(new Fake(200, model("call-3", "propose_purchase", "{\"quoteId\":\"quote-1\"}")));
        REPLIES.add(new Fake(200, stopModel(), delayMillis));
    }
    private void queueHappy() throws Exception {
        REPLIES.add(new Fake(200, model("call-1", "search_offers", "{\"itemId\":\"item-1\"}")));
        REPLIES.add(new Fake(200, model("call-2", "get_quote", "{\"offerId\":\"offer-1\"}")));
        REPLIES.add(new Fake(200, model("call-3", "propose_purchase", "{\"quoteId\":\"quote-1\"}")));
        REPLIES.add(new Fake(200, stopModel()));
    }
    private static String model(String id, String tool, String args) throws Exception {
        return JSON.writeValueAsString(Map.of("model", "qwen3-32b", "choices", List.of(Map.of(
                "finish_reason", "tool_calls", "message", Map.of("tool_calls", List.of(Map.of(
                        "id", id, "type", "function", "function", Map.of("name", tool, "arguments", args)))))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 7, "total_tokens", 17)));
    }
    private static String stopModel() throws Exception {
        return JSON.writeValueAsString(Map.of("model", "qwen3-32b", "choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", "Done"))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 3, "total_tokens", 13)));
    }
    private UUID create(String owner, String max, String recipient) throws Exception {
        ResponseEntity<String> r = createRequest(owner, UUID.randomUUID().toString(), mandate(max, recipient));
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return UUID.fromString(json(r).path("id").asText());
    }
    private JsonNode run(UUID id) throws Exception {
        return json(request("alice", HttpMethod.POST, "/api/executions/" + id + "/run", null));
    }
    private JsonNode evidence(UUID id) throws Exception {
        return json(request("alice", HttpMethod.GET, "/api/executions/" + id + "/evidence.json", null));
    }
    private String lastCode(UUID id) throws Exception {
        JsonNode events = evidence(id).path("events").path("events");
        return events.get(events.size() - 1).path("payload").path("code").asText();
    }
    private static String lastKind(JsonNode export) {
        JsonNode events = export.path("events").path("events");
        return events.get(events.size() - 1).path("kind").asText();
    }
    private static String mandate(String max, String recipient) {
        return "{\"confirmed\":true,\"mandate\":{\"goal\":\"Buy item-1 within budget\",\"itemId\":\"item-1\",\"maxTotal\":\""
                + max + "\",\"currency\":\"TEST_USDC\",\"recipient\":\"" + recipient
                + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}}";
    }
    private ResponseEntity<String> createRequest(String owner, String key, String body) {
        HttpHeaders h = headers(owner); h.set("Idempotency-Key", key);
        return http.exchange("/api/executions", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }
    private ResponseEntity<String> request(String owner, HttpMethod method, String path, String body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(owner)), String.class);
    }
    private static HttpHeaders headers(String owner) {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        if (owner != null) h.setBearerAuth(owner.equals("alice") ? "ci-only-alice-token-0001" : "ci-only-bob-token-00002");
        return h;
    }
    private static JsonNode json(ResponseEntity<String> response) throws Exception { return JSON.readTree(response.getBody()); }
}
