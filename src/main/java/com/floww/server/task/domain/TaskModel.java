package com.floww.server.task.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 저장 모델 (데이터 명세서의 테이블과 1:1). API 응답 모양은 application.TaskViews에서 만든다. */
public final class TaskModel {
    private TaskModel() { }

    public record Task(UUID id, UUID ownerId, String idempotencyKey, String requestHash, TaskStatus status,
                       String statusReasonCode, String goal, Integer currentMandateVersion,
                       Instant createdAt, Instant updatedAt, Instant completedAt) { }

    /** 서버 레지스트리에서 가져온 (merchantId, 수취 주소) 쌍. 위임 승인 범위에 스냅샷으로 저장한다. */
    public record AllowedRecipient(String merchantId, String recipientAddress) { }

    public record Mandate(UUID id, UUID taskId, int version, MandateStatus status, String goal, String itemId,
                          BigInteger budgetBaseUnits, String budgetScope, long chainId, String tokenAddress,
                          int tokenDecimals, List<AllowedRecipient> allowedRecipients, List<String> allowedActions,
                          Instant expiresAt, Instant confirmedAt, String confirmationMethod,
                          String authorizationReference, String mandateHash, Instant createdAt) {
        public Mandate {
            allowedRecipients = List.copyOf(allowedRecipients);
            allowedActions = List.copyOf(allowedActions);
        }

        public boolean allowsRecipient(String merchantId, String recipientAddress) {
            return allowedRecipients.contains(new AllowedRecipient(merchantId, recipientAddress));
        }
    }

    public record Quote(UUID id, UUID taskId, String merchantId, String externalQuoteId, String itemId,
                        String itemName, int quantity, boolean inStock, long chainId, String tokenAddress,
                        int tokenDecimals, BigInteger itemAmountBaseUnits, BigInteger deliveryFeeBaseUnits,
                        BigInteger totalAmountBaseUnits, String quotedPayToAddress,
                        String registryRecipientAddress, String evidenceMode, Instant quotedAt,
                        Instant expiresAt, Instant promisedFulfillmentAt) { }

    public record Attempt(UUID id, UUID taskId, UUID mandateId, UUID quoteId, String proposedQuoteRef,
                          String proposedBy, String proposedRecipient, AttemptStatus status,
                          PolicyDecision decision, String reasonCode, String policyVersion,
                          String payloadHash, BigInteger amountBaseUnits, String recipientAddress,
                          Instant startedAt, Instant finishedAt) { }

    public record ApprovalRequest(String nonce, UUID taskId, UUID attemptId, UUID mandateId,
                                  String typedDataJson, String digest, Instant expiresAt, Instant issuedAt,
                                  Instant consumedAt) { }

    public record Approval(UUID id, UUID taskId, UUID mandateId, UUID attemptId, String nonce, String method,
                           String digest, String signature, String signerAddress, Instant signedAt) { }

    public record Order(UUID id, UUID taskId, UUID attemptId, UUID quoteId, UUID approvalId, String merchantId,
                        String externalOrderId, String idempotencyKey, String requestHash, String status,
                        String paymentStatus, BigInteger amountBaseUnits, String recipientAddress,
                        Instant createdAt) { }

    public record Event(long seq, UUID taskId, UUID attemptId, String kind, String state, String reasonCode,
                        String actor, JsonNode payload, Instant createdAt) { }
}
