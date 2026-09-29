package com.floww.server.task.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.floww.server.common.error.ErrorCode;
import com.floww.server.merchant.MerchantRegistry;
import com.floww.server.task.domain.MandateStatus;
import com.floww.server.task.domain.PolicyDecision;
import com.floww.server.task.domain.TaskModel.AllowedRecipient;
import com.floww.server.task.domain.TaskModel.Mandate;
import com.floww.server.task.domain.TaskModel.Quote;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #34: 결정론적 정책의 각 차단 사유와 우선순위. */
class TaskPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
    private static final String TOKEN = "0x84b494ff145a545d286321691a9b4febe6947d6a";
    private static final String A = "0x00000000000000000000000000000000f10aa001";
    private static final String C = "0x00000000000000000000000000000000f10aa003";
    private static final UUID TASK = UUID.randomUUID();

    private final MerchantRegistry registry = new MerchantRegistry(A,
            "0x00000000000000000000000000000000f10aa002", C);

    private static Mandate mandate(String budget, MandateStatus status, Instant expiresAt) {
        return new Mandate(UUID.randomUUID(), TASK, 1, status, "Buy acetaminophen", "acetaminophen-500mg-10",
                new BigInteger(budget), "TASK_CUMULATIVE", 11155111L, TOKEN, 6,
                List.of(new AllowedRecipient("pharmacy-a", A), new AllowedRecipient("pharmacy-c", C)),
                List.of("PURCHASE"), expiresAt, null, null, null, "0".repeat(64), NOW);
    }

    private static Quote quote(String merchant, String payTo, String registryRecipient, String total, boolean stock,
                               String item, Instant expiresAt) {
        return new Quote(UUID.randomUUID(), TASK, merchant, "qt", item, "Acetaminophen", 1, stock, 11155111L, TOKEN,
                6, new BigInteger(total), BigInteger.ZERO, new BigInteger(total), payTo, registryRecipient,
                "local_pharmacy_simulator", NOW, expiresAt, NOW.plusSeconds(3600));
    }

    private static Quote good(String total) {
        return quote("pharmacy-a", A, A, total, true, "acetaminophen-500mg-10", NOW.plusSeconds(900));
    }

    private TaskPolicy.Decision evaluate(Mandate m, Quote q, String proposed, String consumed) {
        return TaskPolicy.evaluate(m, Optional.ofNullable(q), proposed, registry, new BigInteger(consumed), NOW);
    }

    @Test
    void allowsQuoteWithinCumulativeBudget() {
        TaskPolicy.Decision d = evaluate(mandate("60000000", MandateStatus.DRAFT, NOW.plusSeconds(3600)),
                good("23500000"), null, "0");
        assertTrue(d.allowed());
        assertEquals(PolicyDecision.ALLOW, d.decision());
        assertNull(d.reasonCode());
        // 누적 소비 + 새 견적이 한도와 정확히 같으면 허용
        assertTrue(evaluate(mandate("60000000", MandateStatus.DRAFT, NOW.plusSeconds(3600)),
                good("23500000"), null, "36500000").allowed());
    }

    @Test
    void deniesBudgetExceededUsingTaskCumulativeSpend() {
        Mandate m = mandate("60000000", MandateStatus.DRAFT, NOW.plusSeconds(3600));
        assertEquals(ErrorCode.BUDGET_EXCEEDED, evaluate(m, good("64000000"), null, "0").reasonCode());
        assertEquals(ErrorCode.BUDGET_EXCEEDED, evaluate(m, good("23500000"), null, "36500001").reasonCode());
    }

    @Test
    void deniesAnyRecipientThatIsNotTheRegistryMapping() {
        Mandate m = mandate("60000000", MandateStatus.DRAFT, NOW.plusSeconds(3600));
        // 판매자 견적 payTo가 레지스트리와 다름
        assertEquals(ErrorCode.RECIPIENT_NOT_ALLOWED, evaluate(m, quote("pharmacy-c",
                "0x00000000000000000000000000000000badc0de3", C, "19000000", true, "acetaminophen-500mg-10",
                NOW.plusSeconds(900)), null, "0").reasonCode());
        // AI/클라이언트가 다른 수취인을 제안
        assertEquals(ErrorCode.RECIPIENT_NOT_ALLOWED,
                evaluate(m, good("23500000"), "0x00000000000000000000000000000000badc0de3", "0").reasonCode());
        // 레지스트리에 있지만 mandate 허용 목록에 없는 판매자
        assertEquals(ErrorCode.RECIPIENT_NOT_ALLOWED, evaluate(m, quote("pharmacy-b",
                "0x00000000000000000000000000000000f10aa002", "0x00000000000000000000000000000000f10aa002",
                "10000000", true, "acetaminophen-500mg-10", NOW.plusSeconds(900)), null, "0").reasonCode());
        // 레지스트리에 없는 판매자
        assertEquals(ErrorCode.RECIPIENT_NOT_ALLOWED, evaluate(m, quote("pharmacy-z", A, A, "10000000", true,
                "acetaminophen-500mg-10", NOW.plusSeconds(900)), null, "0").reasonCode());
    }

    @Test
    void deniesExpiredRevokedUnknownStaleWrongItemAndOutOfStock() {
        Mandate live = mandate("60000000", MandateStatus.DRAFT, NOW.plusSeconds(3600));
        assertEquals(ErrorCode.MANDATE_EXPIRED,
                evaluate(mandate("60000000", MandateStatus.DRAFT, NOW), good("1"), null, "0").reasonCode());
        assertEquals(ErrorCode.MANDATE_EXPIRED, evaluate(mandate("60000000", MandateStatus.REVOKED,
                NOW.plusSeconds(3600)), good("1"), null, "0").reasonCode());
        assertEquals(ErrorCode.UNKNOWN_QUOTE_ID, evaluate(live, null, null, "0").reasonCode());
        assertEquals(ErrorCode.QUOTE_STALE, evaluate(live, quote("pharmacy-a", A, A, "1", true,
                "acetaminophen-500mg-10", NOW), null, "0").reasonCode());
        assertEquals(ErrorCode.ITEM_NOT_ALLOWED, evaluate(live, quote("pharmacy-a", A, A, "1", true,
                "ibuprofen-200mg-20", NOW.plusSeconds(900)), null, "0").reasonCode());
        assertEquals(ErrorCode.OUT_OF_STOCK, evaluate(live, quote("pharmacy-a", A, A, "1", false,
                "acetaminophen-500mg-10", NOW.plusSeconds(900)), null, "0").reasonCode());
    }
}
