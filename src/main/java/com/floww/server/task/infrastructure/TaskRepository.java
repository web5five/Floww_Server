package com.floww.server.task.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.task.domain.AttemptStatus;
import com.floww.server.task.domain.MandateStatus;
import com.floww.server.task.domain.PolicyDecision;
import com.floww.server.task.domain.TaskModel.Approval;
import com.floww.server.task.domain.TaskModel.ApprovalRequest;
import com.floww.server.task.domain.TaskModel.AllowedRecipient;
import com.floww.server.task.domain.TaskModel.Attempt;
import com.floww.server.task.domain.TaskModel.Event;
import com.floww.server.task.domain.TaskModel.Mandate;
import com.floww.server.task.domain.TaskModel.Order;
import com.floww.server.task.domain.TaskModel.Quote;
import com.floww.server.task.domain.TaskModel.Task;
import com.floww.server.task.domain.TaskStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Task 관련 테이블 저장소 — Issue #34, #35. JdbcTemplate 기반으로 기존 ExecutionStore·UserRepository와 같은 패턴.
 *
 * <p>소유권은 SQL에서 검사한다 (데이터 명세서 6장: 모든 task 조회는 인증 사용자 소유권을 SQL에서 검사).
 * 상태 전이 규칙은 서비스가 정하고, 이 클래스는 저장만 한다.
 */
@Repository
public class TaskRepository {
    private static final String TASK_COLUMNS = "id, owner_id, idempotency_key, request_hash, status, "
            + "status_reason_code, goal, current_mandate_version, created_at, updated_at, completed_at";
    private static final String MANDATE_COLUMNS = "id, task_id, version, status, goal, item_id, budget_base_units, "
            + "budget_scope, chain_id, token_address, token_decimals, allowed_recipients, allowed_actions, expires_at, "
            + "confirmed_at, confirmation_method, authorization_reference, mandate_hash, created_at";
    private static final String QUOTE_COLUMNS = "id, task_id, merchant_id, external_quote_id, item_id, item_name, "
            + "quantity, in_stock, chain_id, token_address, token_decimals, item_amount_base_units, "
            + "delivery_fee_base_units, total_amount_base_units, quoted_pay_to_address, registry_recipient_address, "
            + "evidence_mode, quoted_at, expires_at, promised_fulfillment_at";
    private static final String ATTEMPT_COLUMNS = "id, task_id, mandate_id, quote_id, proposed_quote_ref, proposed_by, "
            + "proposed_recipient, status, policy_decision, reason_code, policy_version, exact_payload_hash, "
            + "amount_base_units, recipient_address, started_at, finished_at";
    private static final String NONCE_COLUMNS = "nonce, task_id, attempt_id, mandate_id, typed_data, digest, "
            + "expires_at, issued_at, consumed_at";
    private static final String APPROVAL_COLUMNS = "id, task_id, mandate_id, attempt_id, nonce, method, digest, "
            + "signature, signer_address, signed_at";
    private static final String ORDER_COLUMNS = "id, task_id, attempt_id, quote_id, approval_id, merchant_id, "
            + "external_order_id, idempotency_key, request_hash, status, payment_status, amount_base_units, "
            + "recipient_address, created_at";

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public TaskRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ───────────────────────── tasks ─────────────────────────

    /** 같은 (owner, idempotency_key)가 있으면 넣지 않고 false. */
    public boolean insertTask(Task task) {
        return db.update("""
                INSERT INTO tasks(id, owner_id, idempotency_key, request_hash, status, goal, current_mandate_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """, task.id(), task.ownerId(), task.idempotencyKey(), task.requestHash(), task.status().name(),
                task.goal(), task.currentMandateVersion()) == 1;
    }

    public Optional<Task> taskByKey(UUID owner, String key) {
        return db.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE owner_id = ? AND idempotency_key = ?",
                TASK, owner, key).stream().findFirst();
    }

    public Optional<Task> task(UUID owner, UUID id) {
        return db.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE owner_id = ? AND id = ?",
                TASK, owner, id).stream().findFirst();
    }

    /** 같은 Task에 대한 상태 변경을 직렬화한다. 호출 측 트랜잭션 안에서만 쓴다. */
    public Optional<Task> lockTask(UUID owner, UUID id) {
        return db.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE owner_id = ? AND id = ? FOR UPDATE",
                TASK, owner, id).stream().findFirst();
    }

    public List<Task> tasks(UUID owner, int limit) {
        return db.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE owner_id = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", TASK, owner, limit);
    }

    public void updateTaskStatus(UUID id, TaskStatus status, String reasonCode) {
        db.update("UPDATE tasks SET status = ?, status_reason_code = ?, updated_at = now(), "
                        + "completed_at = CASE WHEN ? THEN now() ELSE completed_at END WHERE id = ?",
                status.name(), reasonCode, status.isTerminal(), id);
    }

    public void updateCurrentMandateVersion(UUID id, int version) {
        db.update("UPDATE tasks SET current_mandate_version = ?, updated_at = now() WHERE id = ?", version, id);
    }

    // ───────────────────────── mandate_versions ─────────────────────────

    public void insertMandate(Mandate m) {
        db.update("""
                INSERT INTO mandate_versions(id, task_id, version, status, goal, item_id, budget_base_units,
                    budget_scope, chain_id, token_address, token_decimals, allowed_recipients, allowed_actions,
                    expires_at, mandate_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, m.id(), m.taskId(), m.version(), m.status().name(), m.goal(), m.itemId(),
                new BigDecimal(m.budgetBaseUnits()), m.budgetScope(), m.chainId(), m.tokenAddress(),
                m.tokenDecimals(), write(m.allowedRecipients()), write(m.allowedActions()),
                Timestamp.from(m.expiresAt()), m.mandateHash());
    }

    public Optional<Mandate> mandate(UUID taskId, int version) {
        return db.query("SELECT " + MANDATE_COLUMNS + " FROM mandate_versions WHERE task_id = ? AND version = ?",
                mandateMapper(), taskId, version).stream().findFirst();
    }

    public List<Mandate> mandates(UUID taskId) {
        return db.query("SELECT " + MANDATE_COLUMNS + " FROM mandate_versions WHERE task_id = ? ORDER BY version",
                mandateMapper(), taskId);
    }

    public void updateMandateStatus(UUID id, MandateStatus status) {
        db.update("UPDATE mandate_versions SET status = ? WHERE id = ?", status.name(), id);
    }

    public void confirmMandate(UUID id, String method, String authorizationReference) {
        db.update("UPDATE mandate_versions SET status = 'CONFIRMED', confirmed_at = now(), "
                + "confirmation_method = ?, authorization_reference = ? WHERE id = ? AND status = 'DRAFT'",
                method, authorizationReference, id);
    }

    // ───────────────────────── merchant_quotes ─────────────────────────

    public void insertQuote(Quote q) {
        db.update("""
                INSERT INTO merchant_quotes(id, task_id, merchant_id, external_quote_id, item_id, item_name, quantity,
                    in_stock, chain_id, token_address, token_decimals, item_amount_base_units, delivery_fee_base_units,
                    total_amount_base_units, quoted_pay_to_address, registry_recipient_address, evidence_mode,
                    quoted_at, expires_at, promised_fulfillment_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id, external_quote_id) DO NOTHING
                """, q.id(), q.taskId(), q.merchantId(), q.externalQuoteId(), q.itemId(), q.itemName(), q.quantity(),
                q.inStock(), q.chainId(), q.tokenAddress(), q.tokenDecimals(),
                new BigDecimal(q.itemAmountBaseUnits()), new BigDecimal(q.deliveryFeeBaseUnits()),
                new BigDecimal(q.totalAmountBaseUnits()), q.quotedPayToAddress(), q.registryRecipientAddress(),
                q.evidenceMode(), Timestamp.from(q.quotedAt()), Timestamp.from(q.expiresAt()),
                Timestamp.from(q.promisedFulfillmentAt()));
    }

    /** 아직 만료되지 않은 해당 상품 견적. merchantId 순. */
    public List<Quote> liveQuotes(UUID taskId, String itemId, Instant now) {
        return db.query("SELECT " + QUOTE_COLUMNS + " FROM merchant_quotes WHERE task_id = ? AND item_id = ? "
                + "AND expires_at > ? ORDER BY merchant_id, quoted_at DESC", QUOTE, taskId, itemId, Timestamp.from(now));
    }

    public Optional<Quote> quoteByExternalId(UUID taskId, String externalQuoteId) {
        return db.query("SELECT " + QUOTE_COLUMNS + " FROM merchant_quotes WHERE task_id = ? AND external_quote_id = ?",
                QUOTE, taskId, externalQuoteId).stream().findFirst();
    }

    public Optional<Quote> quote(UUID id) {
        if (id == null) return Optional.empty();
        return db.query("SELECT " + QUOTE_COLUMNS + " FROM merchant_quotes WHERE id = ?", QUOTE, id)
                .stream().findFirst();
    }

    // ───────────────────────── execution_attempts ─────────────────────────

    public void insertAttempt(Attempt a) {
        db.update("""
                INSERT INTO execution_attempts(id, task_id, mandate_id, quote_id, proposed_quote_ref, proposed_by,
                    proposed_recipient, status, policy_decision, reason_code, policy_version, exact_payload_hash,
                    amount_base_units, recipient_address, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.taskId(), a.mandateId(), a.quoteId(), a.proposedQuoteRef(), a.proposedBy(),
                a.proposedRecipient(), a.status().name(), a.decision().name(), a.reasonCode(), a.policyVersion(),
                a.payloadHash(), a.amountBaseUnits() == null ? null : new BigDecimal(a.amountBaseUnits()),
                a.recipientAddress(), Timestamp.from(a.startedAt()),
                a.finishedAt() == null ? null : Timestamp.from(a.finishedAt()));
    }

    public List<Attempt> attempts(UUID taskId) {
        return db.query("SELECT " + ATTEMPT_COLUMNS + " FROM execution_attempts WHERE task_id = ? "
                + "ORDER BY started_at, id", ATTEMPT, taskId);
    }

    public Optional<Attempt> attempt(UUID taskId, UUID attemptId) {
        return db.query("SELECT " + ATTEMPT_COLUMNS + " FROM execution_attempts WHERE task_id = ? AND id = ?",
                ATTEMPT, taskId, attemptId).stream().findFirst();
    }

    public int countAttempts(UUID taskId) {
        Integer count = db.queryForObject("SELECT count(*) FROM execution_attempts WHERE task_id = ?",
                Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public void updateAttemptStatus(UUID attemptId, AttemptStatus status) {
        db.update("UPDATE execution_attempts SET status = ?, finished_at = COALESCE(finished_at, now()) "
                + "WHERE id = ?", status.name(), attemptId);
    }

    /** mandate 수정 시 아직 주문되지 않은 ALLOW 시도를 무효화한다. */
    public int supersedeOpenAttempts(UUID taskId) {
        return db.update("UPDATE execution_attempts SET status = 'SUPERSEDED', finished_at = COALESCE(finished_at, now()) "
                + "WHERE task_id = ? AND status IN ('POLICY_ALLOWED', 'APPROVED')", taskId);
    }

    // ───────────────────────── approval_nonces / mandate_approvals ─────────────────────────

    public void insertApprovalRequest(ApprovalRequest r) {
        db.update("INSERT INTO approval_nonces(nonce, task_id, attempt_id, mandate_id, typed_data, digest, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)", r.nonce(), r.taskId(), r.attemptId(), r.mandateId(),
                r.typedDataJson(), r.digest(), Timestamp.from(r.expiresAt()));
    }

    public Optional<ApprovalRequest> openApprovalRequest(UUID attemptId, UUID mandateId, Instant now) {
        return db.query("SELECT " + NONCE_COLUMNS + " FROM approval_nonces WHERE attempt_id = ? AND mandate_id = ? "
                        + "AND consumed_at IS NULL AND expires_at > ? ORDER BY issued_at DESC LIMIT 1",
                NONCE, attemptId, mandateId, Timestamp.from(now)).stream().findFirst();
    }

    public Optional<ApprovalRequest> approvalRequest(String nonce) {
        return db.query("SELECT " + NONCE_COLUMNS + " FROM approval_nonces WHERE nonce = ?", NONCE, nonce)
                .stream().findFirst();
    }

    /**
     * nonce를 원자적으로 한 번만 소비한다. 이미 소비됐거나 만료됐으면 false.
     * 동시에 두 요청이 와도 UPDATE의 행 잠금 때문에 한 요청만 1을 받는다.
     */
    public boolean consumeNonce(String nonce) {
        return db.update("UPDATE approval_nonces SET consumed_at = clock_timestamp() "
                + "WHERE nonce = ? AND consumed_at IS NULL AND expires_at > clock_timestamp()", nonce) == 1;
    }

    public void insertApproval(Approval a, String typedDataJson) {
        db.update("INSERT INTO mandate_approvals(id, task_id, mandate_id, attempt_id, nonce, method, typed_data, "
                        + "digest, signature, signer_address) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                a.id(), a.taskId(), a.mandateId(), a.attemptId(), a.nonce(), a.method(), typedDataJson, a.digest(),
                a.signature(), a.signerAddress());
    }

    public Optional<Approval> approvalForAttempt(UUID attemptId) {
        return db.query("SELECT " + APPROVAL_COLUMNS + " FROM mandate_approvals WHERE attempt_id = ?",
                APPROVAL, attemptId).stream().findFirst();
    }

    /** 서명자가 Task 소유자에게 연결된 지갑인지 (wallet_identities, V3). */
    public boolean ownerHasWallet(UUID owner, String address) {
        Integer count = db.queryForObject("SELECT count(*) FROM wallet_identities WHERE user_id = ? AND address = ?",
                Integer.class, owner, address);
        return count != null && count > 0;
    }

    // ───────────────────────── merchant_orders ─────────────────────────

    public void insertOrder(Order o) {
        db.update("""
                INSERT INTO merchant_orders(id, task_id, attempt_id, quote_id, approval_id, merchant_id, external_order_id,
                    idempotency_key, request_hash, status, payment_status, amount_base_units, recipient_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, o.id(), o.taskId(), o.attemptId(), o.quoteId(), o.approvalId(), o.merchantId(),
                o.externalOrderId(), o.idempotencyKey(), o.requestHash(), o.status(), o.paymentStatus(),
                new BigDecimal(o.amountBaseUnits()), o.recipientAddress());
    }

    public Optional<Order> orderByKey(UUID taskId, String key) {
        return db.query("SELECT " + ORDER_COLUMNS + " FROM merchant_orders WHERE task_id = ? AND idempotency_key = ?",
                ORDER, taskId, key).stream().findFirst();
    }

    public List<Order> orders(UUID taskId) {
        return db.query("SELECT " + ORDER_COLUMNS + " FROM merchant_orders WHERE task_id = ? ORDER BY created_at, id",
                ORDER, taskId);
    }

    /** Task 전체 누적 지출(예약 포함). 취소되지 않은 주문 금액의 합. */
    public BigInteger consumedBaseUnits(UUID taskId) {
        BigDecimal sum = db.queryForObject("SELECT COALESCE(sum(amount_base_units), 0) FROM merchant_orders "
                + "WHERE task_id = ? AND status <> 'CANCELLED'", BigDecimal.class, taskId);
        return sum == null ? BigInteger.ZERO : sum.toBigIntegerExact();
    }

    // ───────────────────────── task_events ─────────────────────────

    public void appendEvent(UUID taskId, UUID attemptId, String kind, String state, String reasonCode,
                            String actor, Map<String, ?> payload) {
        db.update("INSERT INTO task_events(task_id, attempt_id, kind, state, reason_code, actor, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", taskId, attemptId, kind, state, reasonCode, actor,
                write(payload));
    }

    /** after 다음 seq부터 limit+1개를 읽는다 (hasMore 판단용). */
    public List<Event> events(UUID taskId, long after, int limitPlusOne) {
        return db.query("SELECT seq, task_id, attempt_id, kind, state, reason_code, actor, payload, created_at "
                + "FROM task_events WHERE task_id = ? AND seq > ? ORDER BY seq LIMIT ?", (rs, row) -> new Event(
                rs.getLong("seq"), rs.getObject("task_id", UUID.class), rs.getObject("attempt_id", UUID.class),
                rs.getString("kind"), rs.getString("state"), rs.getString("reason_code"), rs.getString("actor"),
                read(rs.getString("payload")), instant(rs, "created_at")), taskId, after, limitPlusOne);
    }

    // ───────────────────────── mapping ─────────────────────────

    private static final RowMapper<Task> TASK = (rs, row) -> new Task(
            rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("request_hash"), TaskStatus.valueOf(rs.getString("status")),
            rs.getString("status_reason_code"), rs.getString("goal"),
            (Integer) rs.getObject("current_mandate_version"), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "completed_at"));

    private RowMapper<Mandate> mandateMapper() {
        return (rs, row) -> new Mandate(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getInt("version"),
                MandateStatus.valueOf(rs.getString("status")), rs.getString("goal"), rs.getString("item_id"),
                amount(rs, "budget_base_units"), rs.getString("budget_scope"), rs.getLong("chain_id"),
                rs.getString("token_address"), rs.getInt("token_decimals"),
                readList(rs.getString("allowed_recipients"), new TypeReference<List<AllowedRecipient>>() { }),
                readList(rs.getString("allowed_actions"), new TypeReference<List<String>>() { }),
                instant(rs, "expires_at"), instant(rs, "confirmed_at"), rs.getString("confirmation_method"),
                rs.getString("authorization_reference"), rs.getString("mandate_hash"), instant(rs, "created_at"));
    }

    private static final RowMapper<Quote> QUOTE = (rs, row) -> new Quote(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("merchant_id"),
            rs.getString("external_quote_id"), rs.getString("item_id"), rs.getString("item_name"),
            rs.getInt("quantity"), rs.getBoolean("in_stock"), rs.getLong("chain_id"), rs.getString("token_address"),
            rs.getInt("token_decimals"), amount(rs, "item_amount_base_units"), amount(rs, "delivery_fee_base_units"),
            amount(rs, "total_amount_base_units"), rs.getString("quoted_pay_to_address"),
            rs.getString("registry_recipient_address"), rs.getString("evidence_mode"), instant(rs, "quoted_at"),
            instant(rs, "expires_at"), instant(rs, "promised_fulfillment_at"));

    private static final RowMapper<Attempt> ATTEMPT = (rs, row) -> new Attempt(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getObject("mandate_id", UUID.class),
            rs.getObject("quote_id", UUID.class), rs.getString("proposed_quote_ref"), rs.getString("proposed_by"),
            rs.getString("proposed_recipient"), AttemptStatus.valueOf(rs.getString("status")),
            PolicyDecision.valueOf(rs.getString("policy_decision")), rs.getString("reason_code"),
            rs.getString("policy_version"), rs.getString("exact_payload_hash"), amount(rs, "amount_base_units"),
            rs.getString("recipient_address"), instant(rs, "started_at"), instant(rs, "finished_at"));

    private static final RowMapper<ApprovalRequest> NONCE = (rs, row) -> new ApprovalRequest(
            rs.getString("nonce"), rs.getObject("task_id", UUID.class), rs.getObject("attempt_id", UUID.class),
            rs.getObject("mandate_id", UUID.class), rs.getString("typed_data"), rs.getString("digest"),
            instant(rs, "expires_at"), instant(rs, "issued_at"), instant(rs, "consumed_at"));

    private static final RowMapper<Approval> APPROVAL = (rs, row) -> new Approval(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getObject("mandate_id", UUID.class),
            rs.getObject("attempt_id", UUID.class), rs.getString("nonce"), rs.getString("method"),
            rs.getString("digest"), rs.getString("signature"), rs.getString("signer_address"),
            instant(rs, "signed_at"));

    private static final RowMapper<Order> ORDER = (rs, row) -> new Order(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getObject("attempt_id", UUID.class),
            rs.getObject("quote_id", UUID.class), rs.getObject("approval_id", UUID.class), rs.getString("merchant_id"),
            rs.getString("external_order_id"), rs.getString("idempotency_key"), rs.getString("request_hash"),
            rs.getString("status"), rs.getString("payment_status"), amount(rs, "amount_base_units"),
            rs.getString("recipient_address"), instant(rs, "created_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static BigInteger amount(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.toBigIntegerExact();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("task JSON serialization failed", e);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored task JSON is invalid", e);
        }
    }

    private <T> T readList(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored task JSON is invalid", e);
        }
    }
}
