package com.floww.server.aiproposal;

import com.floww.server.aiproposal.MerchantProposal.Context;
import com.floww.server.aiproposal.MerchantProposal.Quote;
import com.floww.server.aiproposal.MerchantProposal.Result;
import com.floww.server.aiproposal.MerchantProposal.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** AI-only freshness seam. The trusted caller supplies owner-scoped snapshots; this grants no authority. */
public final class AiProposalBoundary {
    public record Snapshot(String ownerKey, String taskVersion, String quoteSetVersion,
                           Context context, List<Quote> quotes, Map<String, String> quoteVersions) {
        public Snapshot {
            quotes = quotes == null ? null : Collections.unmodifiableList(new ArrayList<>(quotes));
            quoteVersions = quoteVersions == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(quoteVersions));
        }
    }

    @FunctionalInterface
    public interface CurrentSnapshotReader {
        /** Read authoritative state for this server-authenticated owner and exact task/mandate keys. */
        Snapshot read(String ownerKey, String taskRef, String mandateRef) throws Exception;
    }

    private final AiMerchantProposal proposal;
    private final Clock clock;

    public AiProposalBoundary(AiMerchantProposal proposal, Clock clock) {
        this.proposal = Objects.requireNonNull(proposal);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result propose(Snapshot initial, CurrentSnapshotReader reader) {
        if (initial == null || initial.context() == null || initial.quotes() == null
                || initial.quoteVersions() == null || reader == null
                || !bounded(initial.ownerKey()) || !bounded(initial.taskVersion())
                || !bounded(initial.quoteSetVersion()) || !versionsMatch(initial)) {
            return new Result(Status.CLARIFICATION_REQUIRED, "SNAPSHOT_BOUNDARY_MISSING",
                    initial == null || initial.context() == null ? null : initial.context().taskRef(),
                    initial == null || initial.context() == null ? null : initial.context().mandateRef(),
                    initial == null || initial.context() == null ? null : initial.context().mandateRevision(),
                    null, List.of(), null);
        }

        Result candidate = proposal.propose(initial.context(), initial.quotes());
        if (candidate.status() != Status.PROPOSED) return candidate;
        Snapshot current;
        try {
            current = reader.read(initial.ownerKey(), initial.context().taskRef(), initial.context().mandateRef());
        } catch (Exception failure) {
            return reject(candidate, "SNAPSHOT_READ_FAILED");
        }
        if (current == null) return reject(candidate, "SNAPSHOT_UNAVAILABLE");
        // Full equality also catches same-ID recipient/amount changes and removal of any quoted fact.
        if (!initial.equals(current) || !versionsMatch(current)) return reject(candidate, "SNAPSHOT_STALE");
        Instant now = clock.instant();
        if (!"ACTIVE".equals(current.context().mandateState())
                || !current.context().deadline().isAfter(now)
                || !current.context().requiredFulfillmentBy().isAfter(now)
                || !candidate.proposedQuote().expiresAt().isAfter(now)
                || !candidate.proposedQuote().promisedFulfillmentAt().isAfter(now)) {
            return reject(candidate, "SNAPSHOT_EXPIRED");
        }
        return candidate;
    }

    private static boolean versionsMatch(Snapshot snapshot) {
        if (snapshot.quotes() == null || snapshot.quoteVersions() == null
                || snapshot.quotes().size() > 16 || snapshot.quoteVersions().size() > 16) return false;
        Set<String> ids = new HashSet<>();
        for (Quote quote : snapshot.quotes()) {
            if (quote == null || !bounded(quote.quoteId()) || !ids.add(quote.quoteId())) return false;
        }
        if (!snapshot.quoteVersions().keySet().equals(ids)) return false;
        return snapshot.quoteVersions().values().stream().allMatch(AiProposalBoundary::bounded);
    }

    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl);
    }

    private static Result reject(Result candidate, String reason) {
        return new Result(Status.REJECTED, reason, candidate.taskRef(), candidate.mandateRef(),
                candidate.mandateRevision(), null, candidate.findings(), candidate.provenance());
    }
}
