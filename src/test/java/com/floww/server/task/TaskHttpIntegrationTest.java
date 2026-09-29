package com.floww.server.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.task.infrastructure.TaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/**
 * Issue #34 · #35 (#32): /api/v1/tasks 성공·차단 경로를 실제 PostgreSQL과 JWT로 확인한다.
 *
 * <p>검증 범위: 로컬 약국 시뮬레이터 + 로컬 테스트 지갑 서명. 실제 Kiln, Sepolia 지급, 판매자 이행은 포함하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.auth.wallet.enabled=true",
        "floww.auth.wallet.origin=http://127.0.0.1:8080",
        "floww.auth.wallet.chain-ids=11155111",
        "floww.merchant.test-base-url="
})
class TaskHttpIntegrationTest {
    private static final String ACETAMINOPHEN = "acetaminophen-500mg-10";
    private static final String SPOOFED = "0x00000000000000000000000000000000badc0de3";

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired TaskRepository repository;

    private record Session(String token, String userId, Credentials key) { }

    @Test
    void successPathFromTaskToIdempotentOrderWithTwoBlockedCandidates() throws Exception {
        Session owner = login();
        String key = UUID.randomUUID().toString();
        String body = mandate("60000000");

        ResponseEntity<String> createdResponse = call(HttpMethod.POST, "/api/v1/tasks", owner, key, body);
        JsonNode created = body(createdResponse, 201);
        String taskId = created.path("taskId").asText();
        assertEquals(owner.userId(), created.path("ownerId").asText());
        assertEquals("AWAITING_APPROVAL", created.path("status").asText());
        assertEquals("DRAFT", created.path("mandate").path("status").asText());
        assertEquals("60000000", created.path("mandate").path("maxAmountBaseUnits").asText());
        assertEquals("0", created.path("mandate").path("consumedBaseUnits").asText());
        assertEquals(6, created.path("mandate").path("asset").path("tokenDecimals").asInt());
        assertEquals(3, created.path("mandate").path("allowedRecipients").size());
        assertTrue(created.path("mandate").path("confirmedAt").isNull());
        assertEquals(0, created.path("attempts").size());

        // 같은 키·같은 본문 → 같은 Task(200), 같은 키·다른 본문 → 409
        assertEquals(taskId, body(call(HttpMethod.POST, "/api/v1/tasks", owner, key, body), 200)
                .path("taskId").asText());
        error(call(HttpMethod.POST, "/api/v1/tasks", owner, key, mandate("50000000")), 409, "IDEMPOTENCY_CONFLICT");

        JsonNode quotes = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/quotes", owner, null, null), 200)
                .path("quotes");
        assertEquals(3, quotes.size());
        JsonNode a = quote(quotes, "pharmacy-a"), b = quote(quotes, "pharmacy-b"), c = quote(quotes, "pharmacy-c");
        assertEquals("23500000", a.path("totalAmountBaseUnits").asText());
        assertEquals(SPOOFED, c.path("quotedPayToAddress").asText());
        assertNotEquals(SPOOFED, c.path("recipientAddress").asText());
        // 살아 있는 견적은 다시 불러도 같은 quoteId
        assertEquals(a.path("quoteId").asText(), quote(body(call(HttpMethod.POST,
                "/api/v1/tasks/" + taskId + "/quotes", owner, null, null), 200).path("quotes"), "pharmacy-a")
                .path("quoteId").asText());

        JsonNode context = body(call(HttpMethod.GET, "/api/v1/tasks/" + taskId + "/proposal-context", owner,
                null, null), 200);
        assertEquals("ACTIVE", context.path("mandateState").asText());
        assertEquals("60000000", context.path("maximumTotalBaseUnits").asText());
        assertEquals(3, context.path("quotes").size());
        assertEquals(3, context.path("permittedPairs").size());

        // 차단 1: 예산 초과, 차단 2: 레지스트리와 다른 payTo
        JsonNode overBudget = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt(b.path("quoteId").asText(), "AI", null)), 201);
        assertEquals("DENY", overBudget.path("policy").path("decision").asText());
        assertEquals("BUDGET_EXCEEDED", overBudget.path("policy").path("reasonCode").asText());
        assertEquals("BLOCKED", overBudget.path("status").asText());
        assertEquals("승인한 예산을 초과합니다", overBudget.path("policy").path("message").path("ko").asText());
        JsonNode wrongRecipient = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt(c.path("quoteId").asText(), "AI", null)), 201);
        assertEquals("RECIPIENT_NOT_ALLOWED", wrongRecipient.path("policy").path("reasonCode").asText());
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts/"
                + overBudget.path("attemptId").asText() + "/approval", owner, null, null), 409,
                "INVALID_STATE_TRANSITION");
        // DENY 두 건 뒤에도 유효 후보(A)가 남아 Task는 계속 진행
        assertEquals("AWAITING_APPROVAL", task(owner, taskId).path("status").asText());

        JsonNode allowed = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt(a.path("quoteId").asText(), "AI", null)), 201);
        assertEquals("ALLOW", allowed.path("policy").path("decision").asText());
        assertEquals("POLICY_ALLOWED", allowed.path("status").asText());
        String attemptId = allowed.path("attemptId").asText();

        JsonNode approval = approval(owner, taskId, attemptId);
        assertEquals(approval.path("nonce").asText(), approval(owner, taskId, attemptId).path("nonce").asText());
        JsonNode message = approval.path("typedData").path("message");
        assertEquals("Floww", approval.path("typedData").path("domain").path("name").asText());
        assertEquals(11155111, approval.path("typedData").path("domain").path("chainId").asLong());
        assertEquals(taskId, message.path("taskId").asText());
        assertEquals("pharmacy-a", message.path("merchantId").asText());
        assertEquals(a.path("recipientAddress").asText(), message.path("recipientAddress").asText());
        assertEquals("23500000", message.path("amountBaseUnits").asText());
        assertEquals("60000000", message.path("maxAmountBaseUnits").asText());

        String signature = signTypedData(approval.path("typedData"), owner.key());
        JsonNode confirmed = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, signature)), 200);
        assertEquals("ACTIVE", confirmed.path("status").asText());
        assertEquals("CONFIRMED", confirmed.path("mandate").path("status").asText());
        assertEquals("EIP712", confirmed.path("mandate").path("confirmationMethod").asText());
        assertEquals(approval.path("digest").asText(), confirmed.path("mandate").path("authorizationReference").asText());
        JsonNode approvedAttempt = attemptOf(confirmed, attemptId);
        assertEquals("APPROVED", approvedAttempt.path("status").asText());
        assertEquals(owner.key().getAddress().toLowerCase(), approvedAttempt.path("approval").path("signerAddress").asText());

        // 같은 nonce 재사용 거절 (HTTP + 저장소의 원자적 소비)
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, signature)), 409, "INVALID_STATE_TRANSITION");
        assertFalse(repository.consumeNonce(approval.path("nonce").asText()));

        // 멱등 주문
        String orderKey = UUID.randomUUID().toString();
        String orderBody = json.writeValueAsString(Map.of("attemptId", attemptId));
        JsonNode order = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/orders", owner, orderKey,
                orderBody), 201);
        assertEquals("ACCEPTED", order.path("status").asText());
        assertEquals("NOT_ATTEMPTED", order.path("paymentStatus").asText());
        assertEquals("23500000", order.path("amountBaseUnits").asText());
        assertEquals(a.path("quoteId").asText(), order.path("quoteId").asText());
        assertTrue(order.path("merchantOrderId").asText().startsWith("ord_a_"));
        JsonNode replay = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/orders", owner, orderKey,
                orderBody), 200);
        assertEquals(order.path("orderId").asText(), replay.path("orderId").asText());
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/orders", owner, orderKey,
                json.writeValueAsString(Map.of("attemptId", overBudget.path("attemptId").asText()))), 409,
                "IDEMPOTENCY_CONFLICT");
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/orders", owner, UUID.randomUUID().toString(),
                orderBody), 409, "INVALID_STATE_TRANSITION");

        JsonNode executing = task(owner, taskId);
        assertEquals("EXECUTING", executing.path("status").asText());
        assertEquals("23500000", executing.path("mandate").path("consumedBaseUnits").asText());
        assertEquals("ORDERED", attemptOf(executing, attemptId).path("status").asText());
        assertEquals(order.path("orderId").asText(), attemptOf(executing, attemptId).path("order").path("orderId").asText());
        assertTrue(attemptOf(executing, overBudget.path("attemptId").asText()).path("order").isNull());
        assertTrue(attemptOf(executing, wrongRecipient.path("attemptId").asText()).path("order").isNull());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM merchant_orders WHERE task_id = ?::uuid",
                Integer.class, taskId));

        JsonNode events = body(call(HttpMethod.GET, "/api/v1/tasks/" + taskId + "/events?limit=100", owner, null,
                null), 200).path("events");
        assertTrue(kinds(events).contains("POLICY_DECIDED"));
        assertTrue(kinds(events).contains("MANDATE_CONFIRMED"));
        assertTrue(kinds(events).contains("ORDER_CREATED"));
    }

    @Test
    void modelSuppliedRecipientIsDeniedAndUnknownQuoteIsRecorded() throws Exception {
        Session owner = login();
        String taskId = createTask(owner, "60000000");
        JsonNode a = quote(quotes(owner, taskId), "pharmacy-a");
        JsonNode spoof = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt(a.path("quoteId").asText(), "AI", SPOOFED)), 201);
        assertEquals("DENY", spoof.path("policy").path("decision").asText());
        assertEquals("RECIPIENT_NOT_ALLOWED", spoof.path("policy").path("reasonCode").asText());
        assertEquals(a.path("recipientAddress").asText(), spoof.path("recipientAddress").asText());

        JsonNode unknown = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt("qt_made_up_by_model", "AI", null)), 201);
        assertEquals("UNKNOWN_QUOTE_ID", unknown.path("policy").path("reasonCode").asText());
        assertTrue(unknown.path("merchantId").isNull());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM merchant_orders WHERE task_id = ?::uuid",
                Integer.class, taskId));
    }

    @Test
    void approvalRequiresOwnersWalletAndRejectsForgedSignatures() throws Exception {
        Session owner = login();
        Session other = login();
        String taskId = createTask(owner, "60000000");
        String attemptId = allowPharmacyA(owner, taskId);
        JsonNode approval = approval(owner, taskId, attemptId);

        String stranger = signTypedData(approval.path("typedData"), Credentials.create(Keys.createEcKeyPair()));
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, stranger)), 403, "SIGNER_NOT_TASK_OWNER");
        String otherUser = signTypedData(approval.path("typedData"), other.key());
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, otherUser)), 403, "SIGNER_NOT_TASK_OWNER");
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, "0x" + "11".repeat(64) + "1b")), 401, "SIGNATURE_INVALID");

        // 다른 사용자는 Task 존재 자체를 알 수 없다
        error(call(HttpMethod.GET, "/api/v1/tasks/" + taskId, other, null, null), 404, "TASK_NOT_FOUND");
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", other, null,
                confirm(approval, attemptId, otherUser)), 404, "TASK_NOT_FOUND");
        assertFalse(containsTask(body(call(HttpMethod.GET, "/api/v1/tasks", other, null, null), 200), taskId));
        error(call(HttpMethod.GET, "/api/v1/tasks/" + taskId, null, null, null), 401, "UNAUTHORIZED");

        // 실패한 시도들은 nonce를 소비하지 않았으므로 소유자 서명은 통과
        assertEquals("ACTIVE", body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner,
                null, confirm(approval, attemptId, signTypedData(approval.path("typedData"), owner.key()))), 200)
                .path("status").asText());
    }

    @Test
    void revisedMandateInvalidatesEarlierApproval() throws Exception {
        Session owner = login();
        String taskId = createTask(owner, "60000000");
        String attemptId = allowPharmacyA(owner, taskId);
        JsonNode approval = approval(owner, taskId, attemptId);
        String signature = signTypedData(approval.path("typedData"), owner.key());

        Map<String, Object> revision = new LinkedHashMap<>(mandateMap("30000000"));
        revision.put("baseVersion", 1);
        JsonNode revised = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/revisions", owner, null,
                json.writeValueAsString(revision)), 200);
        assertEquals(2, revised.path("mandate").path("version").asInt());
        assertEquals("SUPERSEDED", attemptOf(revised, attemptId).path("status").asText());
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/confirm", owner, null,
                confirm(approval, attemptId, signature)), 409, "MANDATE_VERSION_MISMATCH");
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/mandate/revisions", owner, null,
                json.writeValueAsString(revision)), 409, "MANDATE_VERSION_MISMATCH");
    }

    @Test
    void attemptLimitDeclinesTaskAndExpiryIsApplied() throws Exception {
        Session owner = login();
        String taskId = createTask(owner, "60000000");
        for (int i = 0; i < 5; i++) {
            body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                    attempt("qt_unknown_" + i, "AI", null)), 201);
        }
        JsonNode declined = task(owner, taskId);
        assertEquals("DECLINED", declined.path("status").asText());
        assertEquals("NO_VALID_CANDIDATE", declined.path("statusReasonCode").asText());
        assertFalse(declined.path("completedAt").isNull());
        error(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt("qt_unknown_6", "AI", null)), 409, "INVALID_STATE_TRANSITION");

        String expiring = createTask(owner, "60000000");
        db.update("UPDATE mandate_versions SET expires_at = now() - interval '1 second' WHERE task_id = ?::uuid",
                expiring);
        JsonNode expired = task(owner, expiring);
        assertEquals("EXPIRED", expired.path("status").asText());
        assertEquals("MANDATE_EXPIRED", expired.path("statusReasonCode").asText());
        assertEquals("EXPIRED", expired.path("mandate").path("status").asText());

        String rejected = createTask(owner, "60000000");
        assertEquals("USER_REJECTED", body(call(HttpMethod.POST, "/api/v1/tasks/" + rejected + "/mandate/reject",
                owner, null, null), 200).path("statusReasonCode").asText());
    }

    @Test
    void clientCannotSelfAuthorizeOrSendDecimalAmounts() throws Exception {
        Session owner = login();
        Map<String, Object> withConfirmed = new LinkedHashMap<>(mandateMap("60000000"));
        withConfirmed.put("confirmed", true);
        error(call(HttpMethod.POST, "/api/v1/tasks", owner, UUID.randomUUID().toString(),
                json.writeValueAsString(withConfirmed)), 400, "INVALID_INPUT");
        for (Object amount : new Object[] {"60.5", "-1", " 60000000", "0", "060000000", 60000000}) {
            Map<String, Object> bad = new LinkedHashMap<>(mandateMap("1"));
            bad.put("maxAmountBaseUnits", amount);
            error(call(HttpMethod.POST, "/api/v1/tasks", owner, UUID.randomUUID().toString(),
                    json.writeValueAsString(bad)), 400, "INVALID_INPUT");
        }
        error(call(HttpMethod.POST, "/api/v1/tasks", owner, null, mandate("60000000")), 400, "INVALID_INPUT");
    }

    // ───────────────────────── helpers ─────────────────────────

    private String createTask(Session owner, String budget) throws Exception {
        return body(call(HttpMethod.POST, "/api/v1/tasks", owner, UUID.randomUUID().toString(), mandate(budget)), 201)
                .path("taskId").asText();
    }

    private JsonNode quotes(Session owner, String taskId) throws Exception {
        return body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/quotes", owner, null, null), 200)
                .path("quotes");
    }

    private String allowPharmacyA(Session owner, String taskId) throws Exception {
        JsonNode a = quote(quotes(owner, taskId), "pharmacy-a");
        JsonNode allowed = body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts", owner, null,
                attempt(a.path("quoteId").asText(), "USER", null)), 201);
        assertEquals("ALLOW", allowed.path("policy").path("decision").asText());
        return allowed.path("attemptId").asText();
    }

    private JsonNode approval(Session owner, String taskId, String attemptId) throws Exception {
        return body(call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/attempts/" + attemptId + "/approval",
                owner, null, null), 200);
    }

    private JsonNode task(Session owner, String taskId) throws Exception {
        return body(call(HttpMethod.GET, "/api/v1/tasks/" + taskId, owner, null, null), 200);
    }

    private Map<String, Object> mandateMap(String budget) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal", "Buy one pack of acetaminophen within 60 fUSDC");
        m.put("itemId", ACETAMINOPHEN);
        m.put("maxAmountBaseUnits", budget);
        m.put("expiresAt", Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
        return m;
    }

    private String mandate(String budget) throws Exception {
        return json.writeValueAsString(mandateMap(budget));
    }

    private String attempt(String quoteId, String proposedBy, String recipient) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteId", quoteId);
        m.put("proposedBy", proposedBy);
        if (recipient != null) m.put("recipientAddress", recipient);
        return json.writeValueAsString(m);
    }

    private String confirm(JsonNode approval, String attemptId, String signature) throws Exception {
        return json.writeValueAsString(Map.of("mandateId", approval.path("mandateId").asText(),
                "version", approval.path("version").asInt(), "attemptId", attemptId,
                "nonce", approval.path("nonce").asText(), "signature", signature));
    }

    /** 지갑이 하듯 typedData만으로 digest를 계산해 서명한다 (서버가 준 digest를 쓰지 않는다). */
    private String signTypedData(JsonNode typedData, Credentials key) throws Exception {
        byte[] digest = new StructuredDataEncoder(json.writeValueAsString(typedData)).hashStructuredData();
        Sign.SignatureData signed = Sign.signMessage(digest, key.getEcKeyPair(), false);
        byte[] out = new byte[65];
        System.arraycopy(signed.getR(), 0, out, 0, 32);
        System.arraycopy(signed.getS(), 0, out, 32, 32);
        out[64] = signed.getV()[0];
        return Numeric.toHexString(out);
    }

    private Session login() throws Exception {
        Credentials key = Credentials.create(Keys.createEcKeyPair());
        JsonNode challenge = body(call(HttpMethod.POST, "/api/v1/auth/wallet/nonce", null, null,
                json.writeValueAsString(Map.of("address", key.getAddress(), "chainId", 11155111))), 200);
        String message = challenge.path("message").asText();
        Sign.SignatureData signed = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),
                key.getEcKeyPair());
        byte[] signature = new byte[65];
        System.arraycopy(signed.getR(), 0, signature, 0, 32);
        System.arraycopy(signed.getS(), 0, signature, 32, 32);
        signature[64] = signed.getV()[0];
        JsonNode verified = body(call(HttpMethod.POST, "/api/v1/auth/wallet/verify", null, null,
                json.writeValueAsString(Map.of("message", message, "signature", Numeric.toHexString(signature)))), 200);
        return new Session(verified.path("accessToken").asText(), verified.path("user").path("userId").asText(), key);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Session session, String idempotencyKey,
                                        String body) {
        HttpHeaders headers = new HttpHeaders();
        if (session != null) headers.setBearerAuth(session.token());
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return json.readTree(response.getBody());
    }

    private void error(ResponseEntity<String> response, int status, String reason) throws Exception {
        assertEquals(reason, body(response, status).path("reasonCode").asText());
    }

    private static JsonNode quote(JsonNode quotes, String merchantId) {
        for (JsonNode q : quotes) if (merchantId.equals(q.path("merchantId").asText())) return q;
        throw new AssertionError("missing quote for " + merchantId);
    }

    private static JsonNode attemptOf(JsonNode task, String attemptId) {
        for (JsonNode a : task.path("attempts")) if (attemptId.equals(a.path("attemptId").asText())) return a;
        throw new AssertionError("missing attempt " + attemptId);
    }

    private static java.util.List<String> kinds(JsonNode events) {
        java.util.List<String> out = new java.util.ArrayList<>();
        events.forEach(e -> out.add(e.path("kind").asText()));
        return out;
    }

    private static boolean containsTask(JsonNode tasks, String taskId) {
        for (JsonNode t : tasks) if (taskId.equals(t.path("taskId").asText())) return true;
        return false;
    }
}
