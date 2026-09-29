package com.floww.server.payment;

import java.util.UUID;

/** Future core/wallet-owned port. There is deliberately no bean or HTTP entry point. */
public interface PaymentGateway {
    record AuthorizedAttempt(UUID executionId, String quoteId, String authorizationReference) { }
    record Submission(String sourceSystem, String sourceFactId) { }
    Submission submit(AuthorizedAttempt attempt);
}
