package com.floww.server.adminaudit;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit read-only projections. Never select JSON payloads, signatures or raw transactions. */
@Repository
public class AdminAuditRepository {
    private final JdbcTemplate db;

    public AdminAuditRepository(JdbcTemplate db) { this.db = db; }

    public record TaskRow(UUID taskId, UUID ownerId, String walletAddress, String goal, String status,
            String statusReasonCode, UUID mandateId, Integer mandateVersion, String mandateStatus,
            String itemId, String maxAmountBaseUnits, String tokenAddress, Integer tokenDecimals,
            Instant mandateExpiresAt, Instant createdAt, Instant updatedAt, Instant completedAt) { }
    public record TaskPage(List<TaskRow> tasks, long total, int page, int limit) { }
    public record AttemptRow(UUID attemptId, String quoteId, String merchantId, String status,
            String policyDecision, String reasonCode, String amountBaseUnits, String recipientAddress,
            Instant createdAt, Instant finishedAt) { }
    public record Detail(TaskRow task, List<AttemptRow> attempts) { }
    public record EventRow(long seq, UUID attemptId, String kind, String state, String reasonCode,
            String actor, Instant createdAt) { }
    public record EventPage(List<EventRow> events, long nextCursor, boolean hasMore) { }
    public record AccountRow(UUID taskId, UUID attemptId, String state, String ownerAddress,
            String accountAddress, String deployTxHash, long chainId, String tokenAddress,
            String recipientAddress, String amountBaseUnits, String approvalTxHash, String approvalOperationState,
            String paymentId, String paymentTxHash, String paymentOperationState,
            Instant paymentVerifiedAt, String fulfillmentId, String fulfillmentEvidenceHash,
            String fulfillmentTxHash, Instant fulfillmentVerifiedAt, Instant updatedAt) { }

    private static final String TASK_SELECT = """
            SELECT t.id task_id, t.owner_id, w.address wallet_address, t.goal, t.status,
              t.status_reason_code, m.id mandate_id, m.version mandate_version,
              m.status mandate_status, m.item_id, m.budget_base_units, m.token_address,
              m.token_decimals, m.expires_at mandate_expires_at, t.created_at, t.updated_at, t.completed_at
            FROM tasks t
            LEFT JOIN LATERAL (SELECT address FROM wallet_identities
                WHERE user_id=t.owner_id ORDER BY is_primary DESC, created_at, id LIMIT 1) w ON true
            LEFT JOIN mandate_versions m ON m.task_id=t.id AND m.version=t.current_mandate_version
            """;

    private static TaskRow task(ResultSet r, int n) throws SQLException {
        return new TaskRow(r.getObject("task_id", UUID.class), r.getObject("owner_id", UUID.class),
                r.getString("wallet_address"), r.getString("goal"), r.getString("status"),
                r.getString("status_reason_code"), r.getObject("mandate_id", UUID.class),
                (Integer) r.getObject("mandate_version"), r.getString("mandate_status"),
                r.getString("item_id"), amount(r, "budget_base_units"),
                r.getString("token_address"), (Integer) r.getObject("token_decimals"), instant(r, "mandate_expires_at"),
                instant(r, "created_at"), instant(r, "updated_at"), instant(r, "completed_at"));
    }

    public TaskPage list(String status, UUID ownerId, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { where.append(" AND t.status=?"); args.add(status); }
        if (ownerId != null) { where.append(" AND t.owner_id=?"); args.add(ownerId); }
        Long total = db.queryForObject("SELECT count(*) FROM tasks t" + where, Long.class, args.toArray());
        args.add(limit);
        args.add((long) page * limit);
        List<TaskRow> rows = db.query(TASK_SELECT + where + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                AdminAuditRepository::task, args.toArray());
        return new TaskPage(rows, total == null ? 0 : total, page, limit);
    }

    public Detail detail(UUID taskId) {
        TaskRow row = db.query(TASK_SELECT + " WHERE t.id=?", AdminAuditRepository::task, taskId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND));
        List<AttemptRow> attempts = db.query("""
                SELECT a.id attempt_id, a.proposed_quote_ref quote_id, q.merchant_id, a.status,
                  a.policy_decision, a.reason_code, a.amount_base_units, a.recipient_address,
                  a.started_at, a.finished_at
                FROM execution_attempts a LEFT JOIN merchant_quotes q ON q.id=a.quote_id
                WHERE a.task_id=? ORDER BY a.started_at, a.id
                """, (r, n) -> new AttemptRow(r.getObject("attempt_id", UUID.class), r.getString("quote_id"),
                r.getString("merchant_id"), r.getString("status"), r.getString("policy_decision"),
                r.getString("reason_code"), amount(r, "amount_base_units"), r.getString("recipient_address"),
                instant(r, "started_at"), instant(r, "finished_at")), taskId);
        return new Detail(row, attempts);
    }

    public EventPage events(UUID taskId, long after, int limit) {
        requireTask(taskId);
        List<EventRow> rows = db.query("""
                SELECT seq, attempt_id, kind, state, reason_code, actor, created_at
                FROM task_events WHERE task_id=? AND seq>? ORDER BY seq LIMIT ?
                """, (r, n) -> new EventRow(r.getLong("seq"), r.getObject("attempt_id", UUID.class),
                r.getString("kind"), r.getString("state"), r.getString("reason_code"),
                r.getString("actor"), instant(r, "created_at")), taskId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<EventRow> visible = more ? rows.subList(0, limit) : rows;
        return new EventPage(visible, visible.isEmpty() ? after : visible.get(visible.size() - 1).seq(), more);
    }

    public AccountRow account(UUID taskId) {
        requireTask(taskId);
        return db.query("""
                SELECT task_id, attempt_id, state, owner_address, account_address, deploy_tx_hash,
                  chain_id, token_address, recipient_address, amount_base_units,
                  (SELECT tx_hash FROM task_account_operations WHERE account_id=a.id AND kind='APPROVAL') approval_tx_hash,
                  (SELECT state FROM task_account_operations WHERE account_id=a.id AND kind='APPROVAL') approval_operation_state,
                  payment_id, payment_tx_hash,
                  (SELECT state FROM task_account_operations WHERE account_id=a.id AND kind='PAYMENT') payment_operation_state,
                  payment_verified_at, fulfillment_id, fulfillment_evidence_hash, fulfillment_tx_hash,
                  fulfillment_verified_at, updated_at
                FROM task_accounts a WHERE task_id=?
                """, (r, n) -> new AccountRow(r.getObject("task_id", UUID.class),
                r.getObject("attempt_id", UUID.class), r.getString("state"), r.getString("owner_address"),
                r.getString("account_address"), r.getString("deploy_tx_hash"), r.getLong("chain_id"),
                r.getString("token_address"), r.getString("recipient_address"), amount(r, "amount_base_units"),
                r.getString("approval_tx_hash"), r.getString("approval_operation_state"),
                r.getString("payment_id"), r.getString("payment_tx_hash"), r.getString("payment_operation_state"),
                instant(r, "payment_verified_at"),
                r.getString("fulfillment_id"), r.getString("fulfillment_evidence_hash"),
                r.getString("fulfillment_tx_hash"), instant(r, "fulfillment_verified_at"),
                instant(r, "updated_at")), taskId).stream().findFirst().orElse(null);
    }

    private void requireTask(UUID taskId) {
        Boolean exists = db.queryForObject("SELECT EXISTS (SELECT 1 FROM tasks WHERE id=?)", Boolean.class, taskId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException(ErrorCode.TASK_NOT_FOUND);
    }
    private static String amount(ResultSet r, String column) throws SQLException {
        BigDecimal value = r.getBigDecimal(column);
        return value == null ? null : value.toBigIntegerExact().toString();
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
