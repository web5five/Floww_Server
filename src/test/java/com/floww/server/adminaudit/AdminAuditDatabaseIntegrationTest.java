package com.floww.server.adminaudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.auth.infrastructure.JwtProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs only against a disposable local PostgreSQL database, with the actual Spring JWT filter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.taskaccount.enabled=false"
})
class AdminAuditDatabaseIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;

    private static final String TOKEN = "0x2222222222222222222222222222222222222222";
    private static final String RECIPIENT = "0x3333333333333333333333333333333333333333";
    private static final String SENSITIVE = "SENSITIVE_FIXTURE_MUST_NOT_LEAVE_DB";

    @Test
    void readsActualTaskAttemptAccountAndEventWithoutSecretsOrClientAccess() throws Exception {
        String ownerAddress = "0x" + UUID.randomUUID().toString().replace("-", "") + "11111111";
        String hash = "0x" + UUID.randomUUID().toString().replace("-", "").repeat(2);
        UUID owner = user();
        UUID other = user();
        db.update("INSERT INTO wallet_identities(id,user_id,address) VALUES(?,?,?)", UUID.randomUUID(), owner, ownerAddress);
        UUID task = task(owner, "ACTIVE");
        UUID second = task(owner, "DRAFT");
        task(other, "DRAFT");
        UUID mandate = UUID.randomUUID();
        db.update("""
                INSERT INTO mandate_versions(id,task_id,version,status,goal,item_id,budget_base_units,
                  chain_id,token_address,token_decimals,allowed_recipients,allowed_actions,expires_at,
                  confirmed_at,confirmation_method,authorization_reference,mandate_hash)
                VALUES(?,?,1,'CONFIRMED','Buy item','item-1',1200000,11155111,?,6,'[]'::jsonb,
                  '[]'::jsonb,now()+interval '1 day',now(),'EIP712','test-reference',?)
                """, mandate, task, TOKEN, "b".repeat(64));
        db.update("UPDATE tasks SET current_mandate_version=1 WHERE id=?", task);
        UUID quote = UUID.randomUUID();
        db.update("""
                INSERT INTO merchant_quotes(id,task_id,merchant_id,external_quote_id,item_id,item_name,
                  quantity,in_stock,chain_id,token_address,token_decimals,item_amount_base_units,
                  delivery_fee_base_units,total_amount_base_units,quoted_pay_to_address,
                  registry_recipient_address,evidence_mode,quoted_at,expires_at,promised_fulfillment_at)
                VALUES(?,?,'pharmacy-a','quote-1','item-1','Item',1,true,11155111,?,6,
                  1000000,200000,1200000,?,?,'SIMULATED',now(),now()+interval '1 hour',now()+interval '2 hours')
                """, quote, task, TOKEN, RECIPIENT, RECIPIENT);
        UUID attempt = UUID.randomUUID();
        db.update("""
                INSERT INTO execution_attempts(id,task_id,mandate_id,quote_id,proposed_quote_ref,
                  proposed_by,status,policy_decision,policy_version,exact_payload_hash,
                  amount_base_units,recipient_address)
                VALUES(?,?,?,?,'quote-1','USER','POLICY_ALLOWED','ALLOW','test',?,1200000,?)
                """, attempt, task, mandate, quote, "c".repeat(64), RECIPIENT);
        db.update("INSERT INTO task_events(task_id,attempt_id,kind,state,actor,payload) VALUES(?,?,'POLICY_CHECK','ALLOW','SYSTEM',?::jsonb)",
                task, attempt, "{\"secret\":\"" + SENSITIVE + "\"}");
        UUID account = UUID.randomUUID();
        db.update("""
                INSERT INTO task_accounts(id,task_id,attempt_id,mandate_id,mandate_version,quote_id,
                  owner_address,chain_id,chain_task_id,review_digest,review_typed_data,token_address,
                  recipient_address,executor_address,reporter_address,amount_base_units,quote_expires_at,
                  expires_at,state)
                VALUES(?,?,?,?,1,?,?,11155111,?,?,?::jsonb,?,?,?,?,1200000,
                  now()+interval '1 hour',now()+interval '1 day','PREPARED')
                """, account, task, attempt, mandate, quote, ownerAddress, hash, hash,
                "{\"secret\":\"" + SENSITIVE + "\"}", TOKEN, RECIPIENT, ownerAddress, ownerAddress);
        db.update("""
                INSERT INTO task_account_operations(id,account_id,kind,signer_address,signer_nonce,
                  raw_transaction,tx_hash,state) VALUES(?,?,'APPROVAL',?,1,?,?,'UNKNOWN')
                """, UUID.randomUUID(), account, ownerAddress, SENSITIVE, hash);

        String admin = token(UserRole.ADMIN, TokenAudience.ADMIN);
        String client = token(UserRole.USER, TokenAudience.CLIENT);
        String base = "/api/v1/admin/audit/tasks";
        assertEquals(401, call(base, null).getStatusCode().value());
        assertEquals(403, call(base, client).getStatusCode().value());
        ResponseEntity<String> page = call(base + "?status=ACTIVE&ownerId=" + owner + "&page=0&limit=1", admin);
        assertEquals(200, page.getStatusCode().value());
        assertEquals("no-store", page.getHeaders().getCacheControl());
        JsonNode pageBody = json.readTree(page.getBody());
        assertEquals(1, pageBody.path("total").asInt());
        assertEquals(task.toString(), pageBody.path("tasks").get(0).path("taskId").asText());
        assertEquals(ownerAddress, pageBody.path("tasks").get(0).path("walletAddress").asText());
        assertEquals(mandate.toString(), pageBody.path("tasks").get(0).path("mandateId").asText());
        JsonNode firstPage = json.readTree(call(base + "?ownerId=" + owner + "&page=0&limit=1", admin).getBody());
        JsonNode nextPage = json.readTree(call(base + "?ownerId=" + owner + "&page=1&limit=1", admin).getBody());
        assertEquals(2, firstPage.path("total").asInt());
        assertEquals(2, nextPage.path("total").asInt());
        assertFalse(firstPage.path("tasks").get(0).path("taskId").asText()
                .equals(nextPage.path("tasks").get(0).path("taskId").asText()));
        assertEquals(Set.of(task.toString(), second.toString()), Set.of(
                firstPage.path("tasks").get(0).path("taskId").asText(),
                nextPage.path("tasks").get(0).path("taskId").asText()));

        JsonNode detail = json.readTree(call(base + "/" + task, admin).getBody());
        assertEquals(attempt.toString(), detail.path("attempts").get(0).path("attemptId").asText());
        assertEquals("pharmacy-a", detail.path("attempts").get(0).path("merchantId").asText());
        JsonNode events = json.readTree(call(base + "/" + task + "/events?limit=1", admin).getBody());
        assertEquals("POLICY_CHECK", events.path("events").get(0).path("kind").asText());
        assertNull(events.path("events").get(0).get("payload"));
        JsonNode accountBody = json.readTree(call(base + "/" + task + "/account", admin).getBody());
        assertEquals(attempt.toString(), accountBody.path("attemptId").asText());
        assertEquals("1200000", accountBody.path("amountBaseUnits").asText());
        assertEquals(hash, accountBody.path("approvalTxHash").asText());
        for (String path : new String[] {base, base + "/" + task, base + "/" + task + "/events",
                base + "/" + task + "/account"}) {
            String body = call(path, admin).getBody();
            assertFalse(body.contains(SENSITIVE));
            assertFalse(body.contains("rawTransaction"));
            assertFalse(body.contains("reviewTypedData"));
            assertFalse(body.contains("approvalSignature"));
            assertEquals(403, call(path, client).getStatusCode().value());
        }
        assertEquals(405, http.exchange(base + "/" + task, HttpMethod.POST,
                new HttpEntity<>("{}", auth(admin)), String.class).getStatusCode().value());
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO users(id,email,password_hash,role,status,provider) VALUES(?,?,'unused','USER','ACTIVE','EMAIL')",
                id, id + "@example.test");
        return id;
    }
    private UUID task(UUID owner, String status) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tasks(id,owner_id,idempotency_key,request_hash,status,goal) VALUES(?,?,?,?,?,'Buy item')",
                id, owner, id.toString(), "d".repeat(64), status);
        return id;
    }
    private String token(UserRole role, TokenAudience audience) {
        Instant now = Instant.now();
        User principal = new User(UUID.randomUUID(), "audit@example.test", null, role, UserStatus.ACTIVE,
                AuthProvider.EMAIL, null, now, now);
        return jwt.issue(principal, audience).value();
    }
    private HttpHeaders auth(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) headers.setBearerAuth(bearer);
        return headers;
    }
    private ResponseEntity<String> call(String path, String bearer) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(bearer)), String.class);
    }
}
