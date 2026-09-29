package com.floww.server.task.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 검증을 통과한 요청 값 (presentation.TaskInputs가 만든다). */
public final class TaskCommands {
    private TaskCommands() { }

    /** allowedMerchantIds가 null이면 레지스트리의 활성 판매자 전체를 허용한다. */
    public record MandateInput(String goal, String itemId, BigInteger maxAmountBaseUnits, Instant expiresAt,
                               List<String> allowedMerchantIds) { }

    public record RevisionInput(int baseVersion, MandateInput mandate) { }

    /** proposedBy: AI | USER. recipientAddress는 신뢰하지 않는 제안 값(소문자), 없으면 null. */
    public record AttemptInput(String quoteId, String proposedBy, String recipientAddress) { }

    public record ConfirmInput(UUID mandateId, int version, UUID attemptId, String nonce, String signature) { }
}
