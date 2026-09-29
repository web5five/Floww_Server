package com.floww.server;

import java.time.Instant;
import java.util.UUID;

/** Future receipt adapter contract; no implementation accepts facts in this slice. */
public interface AuthoritativeFactPort {
    record Fact(UUID executionId, String sourceSystem, String sourceFactId,
                String factType, Instant observedAt, String verifiedSourceReference) { }
    /** Replays with the same sourceSystem/sourceFactId must return the existing fact. */
    Fact recordVerified(Fact fact);
}
