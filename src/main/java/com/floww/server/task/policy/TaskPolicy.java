package com.floww.server.task.policy;

import com.floww.server.common.error.ErrorCode;
import com.floww.server.merchant.MerchantRegistry;
import com.floww.server.task.domain.MandateStatus;
import com.floww.server.task.domain.PolicyDecision;
import com.floww.server.task.domain.TaskModel.Mandate;
import com.floww.server.task.domain.TaskModel.Quote;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

/**
 * 결정론적 지출 정책 — Issue #34 (#32), 정책과 결정 3장.
 *
 * <p>모델·클라이언트·판매자 응답은 입력 데이터일 뿐이다. 이 검사만 ALLOW/DENY를 정하며, 같은 입력이면 항상 같은
 * 결과를 낸다(현재 시각도 인자로 받는다). 검사 순서가 곧 우선순위이고, 첫 번째 위반 사유 하나를 기록한다.
 *
 * <ol>
 *   <li>mandate 유효(REVOKED/EXPIRED 아님, 기한 전) — {@code MANDATE_EXPIRED}</li>
 *   <li>견적 존재·소속 — {@code UNKNOWN_QUOTE_ID}</li>
 *   <li>견적 만료 — {@code QUOTE_STALE}</li>
 *   <li>수취인: 레지스트리 등록·활성, 판매자 payTo = 레지스트리, 제안된 recipient = 레지스트리,
 *       mandate 허용 목록 포함 — {@code RECIPIENT_NOT_ALLOWED}</li>
 *   <li>상품 — {@code ITEM_NOT_ALLOWED}</li>
 *   <li>재고 — {@code OUT_OF_STOCK}</li>
 *   <li>체인·토큰·decimals — {@code CURRENCY_MISMATCH}</li>
 *   <li>Task 누적 지출 + 견적 총액 ≤ 승인 한도 — {@code BUDGET_EXCEEDED}</li>
 * </ol>
 */
public final class TaskPolicy {
    public static final String VERSION = "task-policy-v1";

    public record Decision(PolicyDecision decision, ErrorCode reasonCode) {
        static Decision allow() { return new Decision(PolicyDecision.ALLOW, null); }
        static Decision deny(ErrorCode reason) { return new Decision(PolicyDecision.DENY, reason); }
        public boolean allowed() { return decision == PolicyDecision.ALLOW; }
    }

    private TaskPolicy() { }

    /**
     * @param proposedRecipient AI/클라이언트가 제안에 넣은 수취 주소(소문자 정규화). 없으면 null.
     * @param consumedBaseUnits 이 Task에서 이미 주문(예약)된 금액 합계. 새 견적 금액은 포함하지 않는다.
     */
    public static Decision evaluate(Mandate mandate, Optional<Quote> quote, String proposedRecipient,
                                    MerchantRegistry registry, BigInteger consumedBaseUnits, Instant now) {
        if (mandate.status() == MandateStatus.REVOKED || mandate.status() == MandateStatus.EXPIRED
                || !mandate.expiresAt().isAfter(now)) {
            return Decision.deny(ErrorCode.MANDATE_EXPIRED);
        }
        if (quote.isEmpty() || !quote.get().taskId().equals(mandate.taskId())) {
            return Decision.deny(ErrorCode.UNKNOWN_QUOTE_ID);
        }
        Quote q = quote.get();
        if (!q.expiresAt().isAfter(now)) return Decision.deny(ErrorCode.QUOTE_STALE);

        Optional<MerchantRegistry.Merchant> merchant = registry.find(q.merchantId());
        if (merchant.isEmpty() || !merchant.get().active()) return Decision.deny(ErrorCode.RECIPIENT_NOT_ALLOWED);
        String trusted = merchant.get().recipientAddress();
        if (!trusted.equals(q.registryRecipientAddress()) || !trusted.equals(q.quotedPayToAddress())
                || (proposedRecipient != null && !trusted.equals(proposedRecipient))
                || !mandate.allowsRecipient(q.merchantId(), trusted)) {
            return Decision.deny(ErrorCode.RECIPIENT_NOT_ALLOWED);
        }
        if (!mandate.itemId().equals(q.itemId())) return Decision.deny(ErrorCode.ITEM_NOT_ALLOWED);
        if (!q.inStock()) return Decision.deny(ErrorCode.OUT_OF_STOCK);
        if (mandate.chainId() != q.chainId() || !mandate.tokenAddress().equals(q.tokenAddress())
                || mandate.tokenDecimals() != q.tokenDecimals()) {
            return Decision.deny(ErrorCode.CURRENCY_MISMATCH);
        }
        if (consumedBaseUnits.add(q.totalAmountBaseUnits()).compareTo(mandate.budgetBaseUnits()) > 0) {
            return Decision.deny(ErrorCode.BUDGET_EXCEEDED);
        }
        return Decision.allow();
    }
}
