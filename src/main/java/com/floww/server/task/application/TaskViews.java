package com.floww.server.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.common.error.ErrorResponse;
import com.floww.server.task.domain.BaseUnits;
import com.floww.server.task.domain.TaskModel;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/tasks 응답 모양 — docs/TASK_API_KO_EN.md, docs/openapi.json과 같이 고친다.
 *
 * <p>규칙: camelCase, 금액은 base unit 10진 정수 문자열, 시간은 ISO-8601 UTC, null 필드도 생략하지 않는다.
 * 공개 계약 식별자는 taskId · mandateId · version · attemptId (executionId를 새로 쓰지 않는다).
 */
public final class TaskViews {
    private TaskViews() { }

    public record TaskView(UUID taskId, UUID ownerId, String status, String statusReasonCode, String goal,
                           MandateView mandate, List<AttemptView> attempts, Instant createdAt,
                           Instant updatedAt, Instant completedAt) { }

    public record AssetView(long chainId, String tokenAddress, int tokenDecimals) { }

    public record MandateView(UUID mandateId, int version, String status, String goal, String itemId,
                              String maxAmountBaseUnits, String consumedBaseUnits, String remainingBaseUnits,
                              String budgetScope, AssetView asset, List<TaskModel.AllowedRecipient> allowedRecipients,
                              List<String> allowedActions, Instant expiresAt, Instant confirmedAt,
                              String confirmationMethod, String authorizationReference) {
        static MandateView of(TaskModel.Mandate m, BigInteger consumed) {
            BigInteger remaining = m.budgetBaseUnits().subtract(consumed).max(BigInteger.ZERO);
            return new MandateView(m.id(), m.version(), m.status().name(), m.goal(), m.itemId(),
                    BaseUnits.format(m.budgetBaseUnits()), BaseUnits.format(consumed), BaseUnits.format(remaining),
                    m.budgetScope(), new AssetView(m.chainId(), m.tokenAddress(), m.tokenDecimals()),
                    m.allowedRecipients(), m.allowedActions(), m.expiresAt(), m.confirmedAt(),
                    m.confirmationMethod(), m.authorizationReference());
        }
    }

    public record PolicyView(String decision, String reasonCode, ErrorResponse.Message message,
                             String policyVersion, Instant decidedAt) {
        static PolicyView of(TaskModel.Attempt a) {
            ErrorResponse.Message message = ErrorCode.fromCode(a.reasonCode())
                    .map(code -> new ErrorResponse.Message(code.messageKo(), code.messageEn())).orElse(null);
            return new PolicyView(a.decision().name(), a.reasonCode(), message, a.policyVersion(), a.startedAt());
        }
    }

    public record ApprovalView(String method, String digest, String signerAddress, Instant signedAt) {
        static ApprovalView of(TaskModel.Approval a) {
            return a == null ? null : new ApprovalView(a.method(), a.digest(), a.signerAddress(), a.signedAt());
        }
    }

    public record PaymentView(String status, String txHash) {
        static final PaymentView NOT_ATTEMPTED = new PaymentView("NOT_ATTEMPTED", null);
    }

    public record OrderView(UUID orderId, UUID taskId, UUID attemptId, String quoteId, String merchantId,
                            String merchantOrderId, String status, String paymentStatus, String amountBaseUnits,
                            String recipientAddress, Instant createdAt) {
        static OrderView of(TaskModel.Order o, String externalQuoteId) {
            return o == null ? null : new OrderView(o.id(), o.taskId(), o.attemptId(), externalQuoteId,
                    o.merchantId(), o.externalOrderId(), o.status(), o.paymentStatus(),
                    BaseUnits.format(o.amountBaseUnits()), o.recipientAddress(), o.createdAt());
        }
    }

    public record AttemptView(UUID attemptId, UUID mandateId, int mandateVersion, String quoteId, String merchantId,
                              String proposedBy, String status, String amountBaseUnits, String recipientAddress,
                              PolicyView policy, ApprovalView approval, OrderView order, PaymentView payment,
                              Instant createdAt) { }

    public record QuoteView(String quoteId, String merchantId, String merchantName, String itemId, String itemName,
                            int quantity, boolean inStock, String itemAmountBaseUnits, String deliveryFeeBaseUnits,
                            String totalAmountBaseUnits, AssetView asset, String recipientAddress,
                            String quotedPayToAddress, Instant quotedAt, Instant expiresAt,
                            Instant promisedFulfillmentAt, String evidenceMode) {
        static QuoteView of(TaskModel.Quote q, String merchantName) {
            return new QuoteView(q.externalQuoteId(), q.merchantId(), merchantName, q.itemId(), q.itemName(),
                    q.quantity(), q.inStock(), BaseUnits.format(q.itemAmountBaseUnits()),
                    BaseUnits.format(q.deliveryFeeBaseUnits()), BaseUnits.format(q.totalAmountBaseUnits()),
                    new AssetView(q.chainId(), q.tokenAddress(), q.tokenDecimals()), q.registryRecipientAddress(),
                    q.quotedPayToAddress(), q.quotedAt(), q.expiresAt(), q.promisedFulfillmentAt(), q.evidenceMode());
        }
    }

    public record QuoteList(UUID taskId, int mandateVersion, List<QuoteView> quotes) { }

    /** EIP-712 승인 요청. typedData를 그대로 eth_signTypedData_v4에 넘긴다. */
    public record ApprovalRequestView(UUID taskId, UUID attemptId, UUID mandateId, int version, String nonce,
                                      String digest, Instant expiresAt, Map<String, Object> typedData) { }

    public record EventView(long seq, String kind, String state, String reasonCode, UUID attemptId, String actor,
                            JsonNode payload, Instant createdAt) { }

    public record EventPage(List<EventView> events, long nextCursor, boolean hasMore) { }

    // ───── AI 입력 (#18) — aiproposal.MerchantProposal.Context/Quote 와 1:1 ─────

    public record ProposalAsset(String chainId, String tokenAddress, int decimals) { }

    public record ProposalPair(String merchantId, String recipient) { }

    public record ProposalQuote(String quoteId, String merchantId, String recipient, String itemId,
                                ProposalAsset asset, String totalBaseUnits, Instant expiresAt, boolean inStock,
                                Instant promisedFulfillmentAt, boolean prescriptionRequired,
                                boolean identityRequired) { }

    public record ProposalContext(String taskRef, String mandateRef, String mandateRevision, String itemId,
                                  String maximumTotalBaseUnits, ProposalAsset asset, Instant deadline,
                                  List<ProposalPair> permittedPairs, Instant requiredFulfillmentBy,
                                  Boolean prescriptionEligible, Boolean identityEligible, String mandateState,
                                  List<ProposalQuote> quotes) { }

    /** 서비스 결과 + 새로 만들었는지 여부 (컨트롤러가 201/200을 고른다). */
    public record Created<T>(T body, boolean created) { }
}
