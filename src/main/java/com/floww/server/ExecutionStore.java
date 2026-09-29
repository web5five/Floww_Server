package com.floww.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExecutionStore {
    public record Execution(UUID id, String ownerId, String status, Inputs.Mandate mandate,
                            Instant createdAt, Instant updatedAt) { }
    public record Event(long seq, int schemaVersion, String kind, String actor, String source,
                        UUID correlationId, String toolCallId, String evidenceMode,
                        String modelEvidenceMode,
                        JsonNode payload, Instant createdAt) { }
    public record EventPage(List<Event> events, long nextCursor, boolean hasMore) { }
    public record HistoryPage(List<Execution> executions, UUID nextCursor, boolean hasMore) { }

    private static final String EXECUTION_COLUMNS =
            "id, owner_id, status, goal, item_id, max_total, currency, allowed_recipient, expires_at, created_at, updated_at";
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ExecutionStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public Execution create(String owner, String key, Inputs.Mandate mandate) {
        String canonical = mandate.goal() + "\n" + mandate.itemId() + "\n"
                + mandate.maxTotal().stripTrailingZeros().toPlainString() + "\n"
                + mandate.currency() + "\n" + mandate.recipient() + "\n" + mandate.expiresAt();
        String hash = sha256(canonical);
        UUID id = UUID.randomUUID();
        int inserted = db.update("""
                INSERT INTO executions(id, owner_id, idempotency_key, request_hash,
                    goal, item_id, max_total, currency, allowed_recipient, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED')
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """, id, owner, key, hash, mandate.goal(), mandate.itemId(), mandate.maxTotal(), mandate.currency(),
                mandate.recipient(), Timestamp.from(mandate.expiresAt()));
        if (inserted == 1) {
            append(id, "MANDATE_CONFIRMED", Map.of("goal", mandate.goal(), "itemId", mandate.itemId(),
                    "maxTotal", mandate.maxTotal().toPlainString(), "currency", mandate.currency(),
                    "recipient", mandate.recipient(), "expiresAt", mandate.expiresAt().toString()),
                    "user", null, "mandate");
            return get(owner, id);
        }
        String oldHash = db.queryForObject(
                "SELECT request_hash FROM executions WHERE owner_id = ? AND idempotency_key = ?",
                String.class, owner, key);
        if (!hash.equals(oldHash)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        return db.queryForObject("SELECT " + EXECUTION_COLUMNS
                        + " FROM executions WHERE owner_id = ? AND idempotency_key = ?",
                executionMapper(), owner, key);
    }

    public Execution get(String owner, UUID id) {
        List<Execution> rows = db.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM executions WHERE owner_id = ? AND id = ?", executionMapper(), owner, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND");
        return rows.getFirst();
    }

    public List<Execution> history(String owner, int limit) {
        return db.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM executions WHERE owner_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                executionMapper(), owner, limit);
    }

    public HistoryPage historyPage(String owner, UUID before, int limit) {
        List<Execution> rows;
        if (before == null) {
            rows = db.query("SELECT " + EXECUTION_COLUMNS + " FROM executions WHERE owner_id = ? "
                    + "ORDER BY created_at DESC, id DESC LIMIT ?", executionMapper(), owner, limit + 1);
        } else {
            Execution anchor = get(owner, before);
            rows = db.query("SELECT " + EXECUTION_COLUMNS + " FROM executions WHERE owner_id = ? "
                    + "AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?",
                    executionMapper(), owner, Timestamp.from(anchor.createdAt()), before, limit + 1);
        }
        boolean more = rows.size() > limit;
        List<Execution> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        return new HistoryPage(page, page.isEmpty() ? before : page.getLast().id(), more);
    }

    @Transactional
    public void claim(String owner, UUID id, String evidenceMode) {
        get(owner, id);
        int changed = db.update("UPDATE executions SET status = 'RUNNING', updated_at = now() "
                + "WHERE id = ? AND owner_id = ? AND status = 'CREATED'", id, owner);
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RUN");
        append(id, "RUN_STARTED", Map.of("mode", "quote_policy_precheck_only"),
                "server", null, evidenceMode);
    }

    @Transactional
    public void appendForOwner(String owner, UUID id, String kind, Map<String, ?> payload) {
        get(owner, id);
        append(id, kind, payload, "server", null, "local_test_merchant");
    }

    @Transactional
    public void trace(String owner, UUID id, String kind, Map<String, ?> payload,
                      String actor, String toolCallId, String evidenceMode) {
        get(owner, id);
        append(id, kind, payload, actor, toolCallId, evidenceMode);
    }

    @Transactional
    public void traceModel(String owner, UUID id, String kind, Map<String, ?> payload,
                           String toolCallId, String evidenceMode, String modelEvidenceMode) {
        get(owner, id);
        append(id, kind, payload, modelEvidenceMode, modelEvidenceMode,
                toolCallId, evidenceMode, modelEvidenceMode);
    }

    @Transactional
    public void finish(String owner, UUID id, String status, String kind, Map<String, ?> payload,
                       String evidenceMode) {
        get(owner, id);
        append(id, kind, payload, "server", null, evidenceMode);
        int changed = db.update("UPDATE executions SET status = ?, updated_at = now() "
                + "WHERE id = ? AND owner_id = ? AND status = 'RUNNING'", status, id, owner);
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "NOT_RUNNING");
    }

    @Transactional
    public boolean finishReviewed(String owner, UUID id, String quoteId, Instant deadline,
                                  Map<String, ?> payload, String evidenceMode) {
        get(owner, id);
        int changed = db.update("""
                UPDATE executions e SET status = 'REVIEWED', updated_at = clock_timestamp()
                WHERE e.id = ? AND e.owner_id = ? AND e.status = 'RUNNING'
                  AND e.expires_at > clock_timestamp() AND clock_timestamp() < ?
                  AND EXISTS (SELECT 1 FROM execution_quotes q
                              WHERE q.execution_id = e.id AND q.quote_id = ?
                                AND q.expires_at > clock_timestamp())
                """, id, owner, Timestamp.from(deadline), quoteId);
        if (changed != 1) return false;
        append(id, "POLICY_PRECHECK_PASSED", payload, "server", null, evidenceMode);
        return true;
    }

    public EventPage events(String owner, UUID id, long after, int limit) {
        get(owner, id);
        List<Event> all = db.query("""
                SELECT seq, schema_version, kind, actor, source, correlation_id, tool_call_id,
                       evidence_mode, model_evidence_mode, payload::text AS payload, created_at
                FROM execution_events WHERE execution_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?
                """, (rs, row) -> new Event(rs.getLong("seq"), rs.getInt("schema_version"),
                rs.getString("kind"), rs.getString("actor"), rs.getString("source"),
                rs.getObject("correlation_id", UUID.class), rs.getString("tool_call_id"),
                rs.getString("evidence_mode"), rs.getString("model_evidence_mode"),
                parse(rs.getString("payload")),
                rs.getTimestamp("created_at").toInstant()),
                id, after, limit + 1);
        boolean more = all.size() > limit;
        List<Event> page = new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
        long cursor = page.isEmpty() ? after : page.getLast().seq();
        return new EventPage(page, cursor, more);
    }

    @Transactional
    public void saveQuote(String owner, UUID id, MerchantGateway.Quote quote, String mode) {
        get(owner, id);
        db.update("""
                INSERT INTO execution_quotes(execution_id, quote_id, offer_id, item_id, total_cost,
                    currency, recipient, expires_at, evidence_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (execution_id, quote_id) DO NOTHING
                """, id, quote.quoteId(), quote.offerId(), quote.itemId(), quote.totalCost(),
                quote.currency(), quote.recipient(), Timestamp.from(quote.expiresAt()), mode);
    }

    public MerchantGateway.Quote quote(String owner, UUID id, String quoteId) {
        get(owner, id);
        List<MerchantGateway.Quote> found = db.query("""
                SELECT quote_id, offer_id, item_id, total_cost, currency, recipient, expires_at
                FROM execution_quotes WHERE execution_id = ? AND quote_id = ?
                """, (rs, row) -> new MerchantGateway.Quote(rs.getString("quote_id"),
                rs.getString("offer_id"), rs.getString("item_id"), rs.getBigDecimal("total_cost"),
                rs.getString("currency"), rs.getString("recipient"),
                rs.getTimestamp("expires_at").toInstant()), id, quoteId);
        return found.isEmpty() ? null : found.getFirst();
    }

    public String evidenceMode(String owner, UUID id) {
        Execution execution = get(owner, id);
        if (execution.mandate().itemId() == null) return "legacy_local_precheck";
        Boolean hasQuote = db.queryForObject("SELECT EXISTS(SELECT 1 FROM execution_quotes WHERE execution_id = ?)",
                Boolean.class, id);
        return Boolean.TRUE.equals(hasQuote) ? "local_test_merchant" : "no_merchant_quote";
    }

    public String modelEvidenceMode(String owner, UUID id) {
        get(owner, id);
        List<String> modes = db.query("""
                SELECT DISTINCT model_evidence_mode FROM execution_events
                WHERE execution_id = ? AND kind IN ('MODEL_RESPONSE', 'TOOL_REQUEST')
                """, (rs, row) -> rs.getString(1), id);
        if (modes.isEmpty()) return "none";
        if (modes.size() != 1 || modes.getFirst() == null) return "unknown";
        String mode = modes.getFirst();
        return mode.equals("kiln") || mode.equals("local_model_fixture")
                || mode.equals("unknown_model_provider") ? mode : "unknown";
    }

    public Map<String, Object> modelUsage(String owner, UUID id) {
        get(owner, id);
        List<JsonNode> payloads = db.query("""
                SELECT payload::text FROM execution_events
                WHERE execution_id = ? AND kind = 'MODEL_RESPONSE' ORDER BY seq
                """, (rs, row) -> parse(rs.getString(1)), id);
        if (payloads.isEmpty()) return Map.of("status", "none", "attempts", 0);
        long prompt = 0, completion = 0, total = 0;
        boolean complete = true;
        for (JsonNode payload : payloads) {
            JsonNode usage = payload.path("usage");
            if (!"reported".equals(payload.path("usageStatus").asText())
                    || !validTokens(usage.path("prompt_tokens"))
                    || !validTokens(usage.path("completion_tokens"))
                    || !validTokens(usage.path("total_tokens"))) {
                complete = false;
                continue;
            }
            try {
                prompt = Math.addExact(prompt, usage.path("prompt_tokens").longValue());
                completion = Math.addExact(completion, usage.path("completion_tokens").longValue());
                total = Math.addExact(total, usage.path("total_tokens").longValue());
            } catch (ArithmeticException e) { complete = false; }
        }
        if (!complete) return Map.of("status", "incomplete", "attempts", payloads.size());
        return Map.of("status", "complete", "attempts", payloads.size(),
                "totals", Map.of("promptTokens", prompt, "completionTokens", completion,
                        "totalTokens", total));
    }

    private static boolean validTokens(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0;
    }

    private void append(UUID id, String kind, Map<String, ?> payload, String actor,
                        String toolCallId, String evidenceMode) {
        append(id, kind, payload, actor, "floww_server", toolCallId, evidenceMode, null);
    }

    private void append(UUID id, String kind, Map<String, ?> payload, String actor,
                        String source, String toolCallId, String evidenceMode,
                        String modelEvidenceMode) {
        try {
            db.update("""
                    INSERT INTO execution_events(execution_id, kind, payload, schema_version, actor,
                        source, correlation_id, tool_call_id, evidence_mode, model_evidence_mode)
                    VALUES (?, ?, ?::jsonb, 2, ?, ?, ?, ?, ?, ?)
                    """, id, kind, json.writeValueAsString(payload), actor, source, id,
                    toolCallId, evidenceMode, modelEvidenceMode);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event serialization failed", e);
        }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored event is not JSON", e); }
    }

    private static RowMapper<Execution> executionMapper() {
        return (ResultSet rs, int row) -> new Execution(
                rs.getObject("id", UUID.class), rs.getString("owner_id"), rs.getString("status"),
                new Inputs.Mandate(rs.getString("goal"), rs.getString("item_id"),
                        rs.getBigDecimal("max_total"), rs.getString("currency"),
                        rs.getString("allowed_recipient"), rs.getTimestamp("expires_at").toInstant()),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
