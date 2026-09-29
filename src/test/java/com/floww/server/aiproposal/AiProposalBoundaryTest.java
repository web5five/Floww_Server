package com.floww.server.aiproposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.aiproposal.AiProposalBoundary.Snapshot;
import com.floww.server.aiproposal.MerchantProposal.Asset;
import com.floww.server.aiproposal.MerchantProposal.Context;
import com.floww.server.aiproposal.MerchantProposal.Pair;
import com.floww.server.aiproposal.MerchantProposal.Quote;
import com.floww.server.aiproposal.MerchantProposal.Result;
import com.floww.server.aiproposal.MerchantProposal.Status;
import com.floww.server.integration.kiln.KilnClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiProposalBoundaryTest {
    private static final Instant NOW = Instant.parse("2040-01-01T00:00:00Z");
    private static final Asset ASSET = new Asset("synthetic-chain", "synthetic-token", 6);

    private static final class MutableClock extends Clock {
        private Instant now = NOW;
        void at(Instant value) { now = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FixtureKiln extends KilnClient {
        int calls;
        String arguments = "{\"quoteId\":\"q-a\"}";
        String modelId = MODEL;
        String finishReason = "tool_calls";
        String toolName = "propose_purchase";
        Runnable duringCall = () -> { };
        FixtureKiln() { super(new ObjectMapper(), "http://127.0.0.1:1/v1", "fixture-key"); }
        @Override public KilnClient.Result next(List<Map<String, Object>> messages, List<String> tools, Instant deadline) {
            calls++;
            duringCall.run();
            return new KilnClient.Result(modelId, finishReason, "call-id", toolName, arguments,
                    null, Map.of("total_tokens", 15L), "reported", "0.001", "generation-id", 1);
        }
    }

    private static Context context() {
        return new Context("task-1", "mandate-1", "rev-1", "item", "25000000", ASSET,
                NOW.plusSeconds(3600), List.of(new Pair("pharmacy-a", "recipient-a"),
                        new Pair("pharmacy-b", "recipient-b"), new Pair("pharmacy-c", "recipient-c")),
                NOW.plusSeconds(3000), null, null, "ACTIVE");
    }
    private static Quote quote(String id, String merchant, String recipient, String amount) {
        return new Quote(id, merchant, recipient, "item", ASSET, amount,
                NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false);
    }
    private static Snapshot snapshot() {
        return new Snapshot("server-owner-1", "task-v1", "set-v1", context(),
                List.of(quote("q-a", "pharmacy-a", "recipient-a", "23500000"),
                        quote("q-b", "pharmacy-b", "recipient-b", "24000000"),
                        quote("q-c", "pharmacy-c", "recipient-c", "26000000")),
                Map.of("q-a", "quote-v1", "q-b", "quote-v1", "q-c", "quote-v1"));
    }
    private static Context withState(Context c, String state) {
        return new Context(c.taskRef(), c.mandateRef(), c.mandateRevision(), c.itemId(),
                c.maximumTotalBaseUnits(), c.asset(), c.deadline(), c.permittedPairs(),
                c.requiredFulfillmentBy(), c.prescriptionEligible(), c.identityEligible(), state);
    }
    private static Snapshot draftSnapshot() {
        Snapshot base = snapshot();
        Context c = base.context();
        Context draft = new Context(c.taskRef(), c.mandateRef(), c.mandateRevision(),
                "acetaminophen-500mg-10", "60000000", ASSET, c.deadline(), c.permittedPairs(),
                c.requiredFulfillmentBy(), null, null, "DRAFT");
        List<Quote> quotes = List.of(
                new Quote("q-a", "pharmacy-a", "recipient-a", draft.itemId(), ASSET, "23500000",
                        NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false),
                new Quote("q-b", "pharmacy-b", "recipient-b", draft.itemId(), ASSET, "64000000",
                        NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false),
                new Quote("q-c", "pharmacy-c", "rogue-recipient", draft.itemId(), ASSET, "19000000",
                        NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false));
        return with(base, draft, quotes, base.taskVersion(), base.quoteSetVersion(), base.quoteVersions());
    }
    private static Snapshot with(Snapshot s, Context context, List<Quote> quotes, String taskVersion,
                                 String quoteSetVersion, Map<String, String> versions) {
        return new Snapshot(s.ownerKey(), taskVersion, quoteSetVersion, context, quotes, versions);
    }
    private static AiProposalBoundary boundary(FixtureKiln kiln, MutableClock clock) {
        return new AiProposalBoundary(new AiMerchantProposal(kiln, clock), clock);
    }

    @Test void snapshotCopiesMutableCallerCollectionsBeforeInference() {
        Snapshot base = snapshot();
        List<Quote> quotes = new ArrayList<>(base.quotes());
        Map<String, String> versions = new LinkedHashMap<>(base.quoteVersions());
        Snapshot frozen = new Snapshot(base.ownerKey(), base.taskVersion(), base.quoteSetVersion(),
                base.context(), quotes, versions);
        quotes.clear();
        versions.put("q-a", "tampered");
        assertEquals(3, frozen.quotes().size());
        assertEquals("quote-v1", frozen.quoteVersions().get("q-a"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.quotes().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.quoteVersions().put("q-a", "tampered"));
        FixtureKiln kiln = new FixtureKiln();
        assertEquals(Status.PROPOSED, boundary(kiln, new MutableClock())
                .propose(frozen, (owner, task, mandate) -> frozen).status());
        assertEquals(1, kiln.calls);
    }

    @Test void successfulProposalUsesTrustedKeysAndKeepsExactQuoteAndModelEvidence() {
        FixtureKiln kiln = new FixtureKiln();
        MutableClock clock = new MutableClock();
        Snapshot initial = snapshot();
        AtomicInteger reads = new AtomicInteger();
        Result result = boundary(kiln, clock).propose(initial, (owner, task, mandate) -> {
            reads.incrementAndGet();
            assertEquals(List.of("server-owner-1", "task-1", "mandate-1"), List.of(owner, task, mandate));
            return initial;
        });
        assertEquals(Status.PROPOSED, result.status());
        assertSame(initial.quotes().getFirst(), result.proposedQuote());
        assertEquals("23500000", result.proposedQuote().totalBaseUnits());
        assertEquals("local_model_fixture", result.provenance().modelEvidenceMode());
        assertEquals(15L, result.provenance().usage().get("total_tokens"));
        assertEquals(List.of("OVER_BUDGET"), result.findings().get(2).reasons());
        assertEquals(1, reads.get());
        assertEquals(1, kiln.calls);
    }

    @Test void missingInvalidAndNoCandidateInputsNeverCallModelOrReader() {
        FixtureKiln kiln = new FixtureKiln();
        MutableClock clock = new MutableClock();
        AiProposalBoundary boundary = boundary(kiln, clock);
        AtomicInteger reads = new AtomicInteger();
        AiProposalBoundary.CurrentSnapshotReader reader = (owner, task, mandate) -> {
            reads.incrementAndGet(); return snapshot();
        };
        Snapshot s = snapshot();
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.propose(null, reader).status());
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.propose(
                with(s, s.context(), s.quotes(), null, s.quoteSetVersion(), s.quoteVersions()), reader).status());
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.propose(
                with(s, s.context(), s.quotes(), s.taskVersion(), s.quoteSetVersion(), Map.of()), reader).status());
        Context c = s.context();
        Context missing = new Context(c.taskRef(), c.mandateRef(), c.mandateRevision(), c.itemId(), null,
                c.asset(), c.deadline(), c.permittedPairs(), c.requiredFulfillmentBy(), null, null, "ACTIVE");
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.propose(
                with(s, missing, s.quotes(), s.taskVersion(), s.quoteSetVersion(), s.quoteVersions()), reader).status());
        Quote unknown = new Quote("q-a", "pharmacy-a", "recipient-a", "item", ASSET, "23500000",
                NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, true);
        Snapshot noCandidate = with(s, c, List.of(unknown), s.taskVersion(), s.quoteSetVersion(), Map.of("q-a", "v1"));
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.propose(noCandidate, reader).status());
        assertEquals("ELIGIBILITY_EVIDENCE_MISSING", boundary.propose(noCandidate, reader).reason());
        assertEquals(0, kiln.calls);
        assertEquals(0, reads.get());
    }

    @Test void postInferenceChangesFailClosedWithOriginalModelUsage() {
        Snapshot s = snapshot();
        Quote q = s.quotes().getFirst();
        List<Snapshot> changed = List.of(
                with(s, s.context(), s.quotes(), "task-v2", s.quoteSetVersion(), s.quoteVersions()),
                with(s, s.context(), s.quotes(), s.taskVersion(), "set-v2", s.quoteVersions()),
                with(s, s.context(), s.quotes(), s.taskVersion(), s.quoteSetVersion(),
                        Map.of("q-a", "quote-v2", "q-b", "quote-v1", "q-c", "quote-v1")),
                with(s, s.context(), List.of(quote(q.quoteId(), q.merchantId(), "different-recipient", q.totalBaseUnits()),
                        s.quotes().get(1), s.quotes().get(2)), s.taskVersion(), s.quoteSetVersion(), s.quoteVersions()),
                with(s, s.context(), List.of(quote(q.quoteId(), q.merchantId(), q.recipient(), "23500001"),
                        s.quotes().get(1), s.quotes().get(2)), s.taskVersion(), s.quoteSetVersion(), s.quoteVersions()),
                with(s, s.context(), List.of(s.quotes().get(1), s.quotes().get(2)),
                        s.taskVersion(), s.quoteSetVersion(), Map.of("q-b", "quote-v1", "q-c", "quote-v1")),
                with(s, new Context(s.context().taskRef(), s.context().mandateRef(), "rev-2", s.context().itemId(),
                        s.context().maximumTotalBaseUnits(), ASSET, s.context().deadline(), s.context().permittedPairs(),
                        s.context().requiredFulfillmentBy(), null, null, "ACTIVE"), s.quotes(),
                        s.taskVersion(), s.quoteSetVersion(), s.quoteVersions()),
                new Snapshot("other-owner", s.taskVersion(), s.quoteSetVersion(), s.context(), s.quotes(), s.quoteVersions()));
        for (Snapshot current : changed) {
            FixtureKiln kiln = new FixtureKiln();
            Result result = boundary(kiln, new MutableClock()).propose(s, (owner, task, mandate) -> current);
            assertEquals(Status.REJECTED, result.status());
            assertEquals("SNAPSHOT_STALE", result.reason());
            assertNull(result.proposedQuote());
            assertEquals(15L, result.provenance().usage().get("total_tokens"));
            assertEquals(1, kiln.calls);
        }
    }

    @Test void revocationExpiryAndReadFailuresRejectAfterInference() {
        Snapshot s = snapshot();
        Context c = s.context();
        Context revoked = new Context(c.taskRef(), c.mandateRef(), c.mandateRevision(), c.itemId(),
                c.maximumTotalBaseUnits(), ASSET, c.deadline(), c.permittedPairs(), c.requiredFulfillmentBy(),
                null, null, "REVOKED");
        FixtureKiln kiln = new FixtureKiln();
        MutableClock clock = new MutableClock();
        AiProposalBoundary boundary = boundary(kiln, clock);
        Result revokedResult = boundary.propose(s, (owner, task, mandate) ->
                with(s, revoked, s.quotes(), s.taskVersion(), s.quoteSetVersion(), s.quoteVersions()));
        assertEquals("SNAPSHOT_STALE", revokedResult.reason());
        assertEquals(15L, revokedResult.provenance().usage().get("total_tokens"));
        Result failed = boundary.propose(s, (owner, task, mandate) -> { throw new IllegalStateException("offline"); });
        assertEquals("SNAPSHOT_READ_FAILED", failed.reason());
        assertNull(failed.proposedQuote());
        assertEquals(15L, failed.provenance().usage().get("total_tokens"));
        assertEquals("SNAPSHOT_UNAVAILABLE", boundary.propose(s, (owner, task, mandate) -> null).reason());
        Result afterReadExpiry = boundary.propose(s, (owner, task, mandate) -> {
            clock.at(NOW.plusSeconds(600)); return s;
        });
        assertEquals("SNAPSHOT_EXPIRED", afterReadExpiry.reason());
        assertEquals(15L, afterReadExpiry.provenance().usage().get("total_tokens"));
        clock.at(NOW);
        kiln.duringCall = () -> clock.at(NOW.plusSeconds(600));
        Result expired = boundary.propose(s, (owner, task, mandate) -> s);
        assertEquals(Status.REJECTED, expired.status());
        assertEquals("EXPIRED_DURING_PROPOSAL", expired.reason());
        assertNotNull(expired.provenance());
    }

    @Test void invalidModelOutputCannotReachFreshnessReader() {
        FixtureKiln kiln = new FixtureKiln();
        kiln.arguments = "{\"quoteId\":\"unknown\"}";
        AtomicInteger reads = new AtomicInteger();
        Result result = boundary(kiln, new MutableClock()).propose(snapshot(), (owner, task, mandate) -> {
            reads.incrementAndGet(); return snapshot();
        });
        assertEquals(Status.REJECTED, result.status());
        assertEquals("MODEL_QUOTE_NOT_ELIGIBLE", result.reason());
        assertNotNull(result.provenance());
        assertEquals(0, reads.get());
    }

    @Test void draftPreapprovalSelectsOnlyEligibleSyntheticQuoteAndLegacyStillRejectsDraft() {
        Snapshot draft = draftSnapshot();
        FixtureKiln kiln = new FixtureKiln();
        AiProposalBoundary boundary = boundary(kiln, new MutableClock());
        assertEquals("MANDATE_INACTIVE_OR_EXPIRED", boundary.propose(draft, (o, t, m) -> draft).reason());
        assertEquals(0, kiln.calls);
        Result result = boundary.proposePreapproval(draft, (owner, task, mandate) -> {
            assertEquals(List.of("server-owner-1", "task-1", "mandate-1"), List.of(owner, task, mandate));
            return draft;
        });
        assertEquals(Status.PROPOSED, result.status());
        assertSame(draft.quotes().getFirst(), result.proposedQuote());
        assertEquals("q-a", result.proposedQuote().quoteId());
        assertEquals(List.of(), result.findings().get(0).reasons());
        assertEquals(List.of("OVER_BUDGET"), result.findings().get(1).reasons());
        assertEquals(List.of("RECIPIENT_NOT_PERMITTED"), result.findings().get(2).reasons());
        assertEquals("DRAFT", draft.context().mandateState());
        assertEquals(15L, result.provenance().usage().get("total_tokens"));
        assertEquals(1, kiln.calls);
    }

    @Test void preapprovalRequiresExplicitDraftAndCompleteValidBoundaryBeforeModelCall() {
        Snapshot draft = draftSnapshot();
        FixtureKiln kiln = new FixtureKiln();
        AiProposalBoundary boundary = boundary(kiln, new MutableClock());
        for (String state : List.of("ACTIVE", "CONFIRMED", "REVOKED", "EXPIRED", "COMPLETED", "UNKNOWN", "draft")) {
            Snapshot invalid = with(draft, withState(draft.context(), state), draft.quotes(),
                    draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions());
            assertEquals(Status.REJECTED, boundary.proposePreapproval(invalid, (o, t, m) -> invalid).status(), state);
        }
        Snapshot absentState = with(draft, withState(draft.context(), null), draft.quotes(),
                draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions());
        assertEquals(Status.CLARIFICATION_REQUIRED, boundary.proposePreapproval(absentState, (o, t, m) -> absentState).status());
        assertEquals("SNAPSHOT_BOUNDARY_MISSING", boundary.proposePreapproval(null, (o, t, m) -> draft).reason());
        assertEquals("SNAPSHOT_BOUNDARY_MISSING", boundary.proposePreapproval(draft, null).reason());
        Context c = draft.context();
        Context badBudget = new Context(c.taskRef(), c.mandateRef(), c.mandateRevision(), c.itemId(),
                "60.0", c.asset(), c.deadline(), c.permittedPairs(), c.requiredFulfillmentBy(), null, null, "DRAFT");
        assertEquals("INPUT_INVALID", boundary.proposePreapproval(with(draft, badBudget, draft.quotes(),
                draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions()), (o, t, m) -> draft).reason());
        List<Quote> invalidAmounts = List.of(
                new Quote("q-a", "pharmacy-a", "recipient-a", c.itemId(), ASSET, "0",
                        NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false));
        Snapshot noCandidate = with(draft, c, invalidAmounts, draft.taskVersion(), draft.quoteSetVersion(),
                Map.of("q-a", "quote-v1"));
        Result none = boundary.proposePreapproval(noCandidate, (o, t, m) -> noCandidate);
        assertEquals(Status.NO_CANDIDATE, none.status());
        assertNull(none.proposedQuote());
        assertEquals(List.of("QUOTE_MALFORMED"), none.findings().getFirst().reasons());
        Quote evidenceMissing = new Quote("q-a", "pharmacy-a", "recipient-a", c.itemId(), ASSET,
                "23500000", NOW.plusSeconds(600), true, NOW.plusSeconds(2400), true, false);
        Snapshot incomplete = with(draft, c, List.of(evidenceMissing), draft.taskVersion(),
                draft.quoteSetVersion(), Map.of("q-a", "quote-v1"));
        assertEquals("ELIGIBILITY_EVIDENCE_MISSING",
                boundary.proposePreapproval(incomplete, (o, t, m) -> incomplete).reason());
        assertEquals(0, kiln.calls);
        kiln.arguments = "{\"quoteId\":\"q-b\"}";
        assertEquals("MODEL_QUOTE_NOT_ELIGIBLE", boundary.proposePreapproval(draft, (o, t, m) -> draft).reason());
        assertEquals(1, kiln.calls);
    }

    @Test void preapprovalRejectsInvalidModelArgumentsWithoutFreshReadOrSelection() {
        Snapshot draft = draftSnapshot();
        for (String raw : List.of("{\"quoteId\":\"q-a\",\"extra\":true}",
                "{\"quoteId\":\"q-a\",\"quoteId\":\"q-b\"}",
                "{\"quoteId\":1}", "{\"quoteId\":\"q-a\"} {}")) {
            FixtureKiln kiln = new FixtureKiln();
            kiln.arguments = raw;
            AtomicInteger reads = new AtomicInteger();
            Result result = boundary(kiln, new MutableClock()).proposePreapproval(draft, (o, t, m) -> {
                reads.incrementAndGet(); return draft;
            });
            assertEquals(Status.MODEL_FAILURE, result.status(), raw);
            assertNull(result.proposedQuote());
            assertEquals(15L, result.provenance().usage().get("total_tokens"));
            assertEquals(0, reads.get());
        }
        FixtureKiln wrongModel = new FixtureKiln();
        wrongModel.modelId = "other-model";
        assertEquals("MODEL_MISMATCH", boundary(wrongModel, new MutableClock())
                .proposePreapproval(draft, (o, t, m) -> draft).reason());
        FixtureKiln wrongTool = new FixtureKiln();
        wrongTool.toolName = "other_tool";
        assertEquals("MODEL_TOOL_INVALID", boundary(wrongTool, new MutableClock())
                .proposePreapproval(draft, (o, t, m) -> draft).reason());
    }

    @Test void preapprovalFreshnessRejectsStateAndSnapshotChangesAfterInference() {
        Snapshot draft = draftSnapshot();
        List<Snapshot> changed = List.of(
                new Snapshot("other-owner", draft.taskVersion(), draft.quoteSetVersion(), draft.context(),
                        draft.quotes(), draft.quoteVersions()),
                with(draft, draft.context(), draft.quotes(), "task-v2", draft.quoteSetVersion(), draft.quoteVersions()),
                with(draft, draft.context(), draft.quotes(), draft.taskVersion(), "set-v2", draft.quoteVersions()),
                with(draft, draft.context(), draft.quotes(), draft.taskVersion(), draft.quoteSetVersion(),
                        Map.of("q-a", "quote-v2", "q-b", "quote-v1", "q-c", "quote-v1")),
                with(draft, new Context(draft.context().taskRef(), draft.context().mandateRef(), "rev-2",
                        draft.context().itemId(), draft.context().maximumTotalBaseUnits(), ASSET,
                        draft.context().deadline(), draft.context().permittedPairs(),
                        draft.context().requiredFulfillmentBy(), null, null, "DRAFT"),
                        draft.quotes(), draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions()),
                with(draft, draft.context(), List.of(
                        new Quote("q-a", "pharmacy-a", "recipient-a", draft.context().itemId(), ASSET,
                                "23500001", NOW.plusSeconds(600), true, NOW.plusSeconds(2400), false, false),
                        draft.quotes().get(1), draft.quotes().get(2)), draft.taskVersion(),
                        draft.quoteSetVersion(), draft.quoteVersions()),
                with(draft, draft.context(), draft.quotes().subList(1, 3), draft.taskVersion(),
                        draft.quoteSetVersion(), Map.of("q-b", "quote-v1", "q-c", "quote-v1")),
                with(draft, withState(draft.context(), "REVOKED"), draft.quotes(),
                        draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions()),
                with(draft, withState(draft.context(), "ACTIVE"), draft.quotes(),
                        draft.taskVersion(), draft.quoteSetVersion(), draft.quoteVersions()));
        for (Snapshot current : changed) {
            FixtureKiln kiln = new FixtureKiln();
            Result result = boundary(kiln, new MutableClock()).proposePreapproval(draft, (o, t, m) -> current);
            assertEquals("SNAPSHOT_STALE", result.reason());
            assertNull(result.proposedQuote());
            assertEquals(15L, result.provenance().usage().get("total_tokens"));
            assertEquals(1, kiln.calls);
        }
        MutableClock clock = new MutableClock();
        Result expired = boundary(new FixtureKiln(), clock).proposePreapproval(draft, (o, t, m) -> {
            clock.at(NOW.plusSeconds(600)); return draft;
        });
        assertEquals("SNAPSHOT_EXPIRED", expired.reason());
        assertNull(expired.proposedQuote());
        assertEquals(15L, expired.provenance().usage().get("total_tokens"));
    }
}
