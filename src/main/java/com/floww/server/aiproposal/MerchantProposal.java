package com.floww.server.aiproposal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Candidate internal data contract. All context fields are trusted-caller snapshots, not payment authority. */
public final class MerchantProposal {
    private MerchantProposal() { }

    public record Asset(String chainId, String tokenAddress, int decimals) { }
    public record Pair(String merchantId, String recipient) { }
    public record Context(String taskRef, String mandateRef, String mandateRevision, String itemId,
                          String maximumTotalBaseUnits, Asset asset, Instant deadline,
                          List<Pair> permittedPairs, Instant requiredFulfillmentBy,
                          Boolean prescriptionEligible, Boolean identityEligible, String mandateState) {
        public Context { permittedPairs = permittedPairs == null ? null
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(permittedPairs)); }
    }
    public record Quote(String quoteId, String merchantId, String recipient, String itemId,
                        Asset asset, String totalBaseUnits, Instant expiresAt, boolean inStock,
                        Instant promisedFulfillmentAt, boolean prescriptionRequired,
                        boolean identityRequired) { }
    public enum Status { PROPOSED, CLARIFICATION_REQUIRED, NO_CANDIDATE, REJECTED, MODEL_FAILURE }
    public record Finding(String quoteId, List<String> reasons) {
        public Finding { reasons = List.copyOf(reasons); }
    }
    public record Provenance(String modelId, String modelEvidenceMode, String finishReason,
                             String toolCallId, String generationId, String usageStatus,
                             Map<String, Long> usage, String cost, int attempts) {
        public Provenance { usage = usage == null ? Map.of() : Map.copyOf(usage); }
    }
    public record Result(Status status, String reason, String taskRef, String mandateRef,
                         String mandateRevision, Quote proposedQuote, List<Finding> findings,
                         Provenance provenance) {
        public Result { findings = List.copyOf(findings); }
    }
}
