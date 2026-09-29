package com.floww.server.aidraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewConfirmationBindingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant created = Instant.parse("2026-09-29T00:00:00Z");
    private final Clock clock = at("2026-09-29T01:00:00Z");
    private final ReviewConfirmationBinding.Context context =
            new ReviewConfirmationBinding.Context("owner-1", "task-1", 3, created);

    private Clock at(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }

    private ObjectNode draft() throws Exception {
        return (ObjectNode) json.readTree("""
                {"schemaVersion":"ai-draft.v1","objective":"Obtain one readable report",
                 "itemScope":"One PDF report on the requested public dataset",
                 "providerCriteria":"A provider able to deliver the requested PDF",
                 "maximumTotalCost":{"amount":"0.000001","asset":"ETH","includesAllUserPaidFees":true},
                 "deadline":"2026-09-29T02:00:00Z",
                 "fulfillmentCriterion":"A PDF containing the requested public data tables is delivered"}
                """);
    }

    private ReviewConfirmationBinding.Snapshot snapshot() throws Exception {
        var result = ReviewConfirmationBinding.prepare(draft(), context, clock);
        assertTrue(result.ready(), result.reasonCode());
        return result.snapshot();
    }

    private ReviewConfirmationBinding.Receipt receipt(ReviewConfirmationBinding.Snapshot snapshot) {
        return new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 3, snapshot.digest(), true,
                Instant.parse("2026-09-29T00:30:00Z"));
    }

    private void rejected(String reason, ReviewConfirmationBinding.Snapshot snapshot, ObjectNode draft,
                          ReviewConfirmationBinding.Context current, ReviewConfirmationBinding.Receipt receipt,
                          Clock now) {
        var verdict = ReviewConfirmationBinding.verify(snapshot, draft, current, receipt, now);
        assertEquals("CONFIRMATION_REJECTED", verdict.status());
        assertEquals(reason, verdict.reasonCode());
    }

    @Test void populatedDraftAloneIsUnapprovedButTrustedReceiptMatchesOnlyBinding() throws Exception {
        assertEquals("READY_FOR_REVIEW", AiDraftPreflight.evaluate(draft(), clock).status());
        var snapshot = snapshot();
        rejected("RECEIPT_REQUIRED", snapshot, draft(), context, null, clock);
        var verdict = ReviewConfirmationBinding.verify(snapshot, draft(), context, receipt(snapshot), clock);
        assertEquals("CONFIRMATION_MATCHED", verdict.status());
        assertNull(verdict.reasonCode());
        assertEquals("review-confirmation.v1", snapshot.contractVersion());
    }

    @Test void everyDescriptiveAndFinancialTermIsBoundExactly() throws Exception {
        var snapshot = snapshot();
        for (String field : List.of("objective", "itemScope", "providerCriteria", "fulfillmentCriterion")) {
            var changed = draft();
            changed.put(field, changed.get(field).textValue() + " amended");
            rejected("CURRENT_DRAFT_CHANGED", snapshot, changed, context, receipt(snapshot), clock);
        }
        for (String field : List.of("amount", "asset")) {
            var changed = draft();
            ((ObjectNode) changed.get("maximumTotalCost")).put(field,
                    field.equals("amount") ? "0.0000010" : "USD");
            rejected("CURRENT_DRAFT_CHANGED", snapshot, changed, context, receipt(snapshot), clock);
        }
        var deadline = draft();
        deadline.put("deadline", "2026-09-29T03:00:00Z");
        rejected("CURRENT_DRAFT_CHANGED", snapshot, deadline, context, receipt(snapshot), clock);
        var fees = draft();
        ((ObjectNode) fees.get("maximumTotalCost")).put("includesAllUserPaidFees", false);
        rejected("CURRENT_DRAFT_NOT_READY", snapshot, fees, context, receipt(snapshot), clock);
    }

    @Test void currentAndReceiptContextCannotBeReused() throws Exception {
        var snapshot = snapshot();
        var receipt = receipt(snapshot);
        rejected("OWNER_MISMATCH", snapshot, draft(),
                new ReviewConfirmationBinding.Context("owner-2", "task-1", 3, created), receipt, clock);
        rejected("TASK_MISMATCH", snapshot, draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task-2", 3, created), receipt, clock);
        rejected("REVISION_MISMATCH", snapshot, draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task-1", 4, created), receipt, clock);
        rejected("CREATION_TIME_MISMATCH", snapshot, draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task-1", 3, created.plusSeconds(1)), receipt, clock);
        rejected("RECEIPT_OWNER_MISMATCH", snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt("owner-2", "task-1", 3, snapshot.digest(), true, receipt.confirmedAt()), clock);
        rejected("RECEIPT_TASK_MISMATCH", snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt("owner-1", "task-2", 3, snapshot.digest(), true, receipt.confirmedAt()), clock);
        rejected("RECEIPT_REVISION_MISMATCH", snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 4, snapshot.digest(), true, receipt.confirmedAt()), clock);
        rejected("RECEIPT_DIGEST_MISMATCH", snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 3, "a".repeat(64), true, receipt.confirmedAt()), clock);
    }

    @Test void missingWithdrawnMalformedAndMistimedReceiptReject() throws Exception {
        var snapshot = snapshot();
        rejected("RECEIPT_REQUIRED", snapshot, draft(), context, null, clock);
        for (Boolean confirmed : new Boolean[]{false, null})
            rejected("CONFIRMATION_NOT_ACTIVE", snapshot, draft(), context,
                    new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 3, snapshot.digest(), confirmed, created), clock);
        rejected("RECEIPT_MALFORMED", snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt(" owner-1", "task-1", 3, snapshot.digest(), true, created), clock);
        for (Instant time : new Instant[]{null, created.minusNanos(1), clock.instant().plusNanos(1)})
            rejected("CONFIRMATION_TIME_INVALID", snapshot, draft(), context,
                    new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 3, snapshot.digest(), true, time), clock);
        assertEquals("CONFIRMATION_MATCHED", ReviewConfirmationBinding.verify(snapshot, draft(), context,
                new ReviewConfirmationBinding.Receipt("owner-1", "task-1", 3, snapshot.digest(), true, created), clock).status());
    }

    @Test void exactExpiryAndMalformedPreparationRejectWithoutExceptions() throws Exception {
        var snapshot = snapshot();
        rejected("SNAPSHOT_DRAFT_NOT_READY", snapshot, draft(), context, receipt(snapshot), at("2026-09-29T02:00:00Z"));
        assertEquals("CLOCK_REQUIRED", ReviewConfirmationBinding.prepare(draft(), context, null).reasonCode());
        assertEquals("CONTEXT_REQUIRED", ReviewConfirmationBinding.prepare(draft(), null, clock).reasonCode());
        assertEquals("OWNER_ID_INVALID", ReviewConfirmationBinding.prepare(draft(),
                new ReviewConfirmationBinding.Context(" ", "task-1", 3, created), clock).reasonCode());
        assertEquals("TASK_ID_INVALID", ReviewConfirmationBinding.prepare(draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task 1", 3, created), clock).reasonCode());
        assertEquals("REVISION_INVALID", ReviewConfirmationBinding.prepare(draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task-1", 0, created), clock).reasonCode());
        assertEquals("CREATION_TIME_INVALID", ReviewConfirmationBinding.prepare(draft(),
                new ReviewConfirmationBinding.Context("owner-1", "task-1", 3, clock.instant().plusSeconds(1)), clock).reasonCode());
        assertEquals("DRAFT_NOT_READY", ReviewConfirmationBinding.prepare(null, context, clock).reasonCode());
        assertEquals("CURRENT_DRAFT_NOT_READY", ReviewConfirmationBinding.verify(snapshot, null, context,
                receipt(snapshot), clock).reasonCode());
    }

    @Test void keyOrderAndCallerMutationCannotAlterSnapshot() throws Exception {
        var original = draft();
        var snapshot = ReviewConfirmationBinding.prepare(original, context, clock).snapshot();
        var reordered = (ObjectNode) json.readTree("""
                {"fulfillmentCriterion":"A PDF containing the requested public data tables is delivered",
                 "deadline":"2026-09-29T02:00:00Z",
                 "maximumTotalCost":{"includesAllUserPaidFees":true,"asset":"ETH","amount":"0.000001"},
                 "providerCriteria":"A provider able to deliver the requested PDF",
                 "itemScope":"One PDF report on the requested public dataset",
                 "objective":"Obtain one readable report","schemaVersion":"ai-draft.v1"}
                """);
        assertEquals(snapshot.digest(), ReviewConfirmationBinding.prepare(reordered, context, clock).snapshot().digest());
        original.put("objective", "Caller changed original");
        ((ObjectNode) snapshot.draft()).put("objective", "Caller changed returned copy");
        ((ObjectNode) snapshot.draft().get("maximumTotalCost")).put("amount", "999");
        assertEquals("CONFIRMATION_MATCHED", ReviewConfirmationBinding.verify(snapshot, reordered, context,
                receipt(snapshot), clock).status());
    }

    @Test void lengthAndStructureCannotCollideByConcatenation() throws Exception {
        var a = draft();
        var b = draft();
        a.put("objective", "ab"); a.put("itemScope", "c");
        b.put("objective", "a"); b.put("itemScope", "bc");
        assertNotEquals(ReviewConfirmationBinding.prepare(a, context, clock).snapshot().digest(),
                ReviewConfirmationBinding.prepare(b, context, clock).snapshot().digest());
        a.put("objective", "x|itemScope:y"); a.put("itemScope", "z");
        b.put("objective", "x"); b.put("itemScope", "y|itemScope:z");
        assertNotEquals(ReviewConfirmationBinding.prepare(a, context, clock).snapshot().digest(),
                ReviewConfirmationBinding.prepare(b, context, clock).snapshot().digest());
        var differentContext = new ReviewConfirmationBinding.Context("owner-1", "task-1", 4, created);
        assertNotEquals(ReviewConfirmationBinding.prepare(a, context, clock).snapshot().digest(),
                ReviewConfirmationBinding.prepare(a, differentContext, clock).snapshot().digest());
        var laterCreation = new ReviewConfirmationBinding.Context("owner-1", "task-1", 3, created.plusNanos(1));
        assertNotEquals(ReviewConfirmationBinding.prepare(a, context, clock).snapshot().digest(),
                ReviewConfirmationBinding.prepare(a, laterCreation, clock).snapshot().digest());
        a.put("objective", String.valueOf((char) 0xd800));
        b.put("objective", String.valueOf((char) 0xd801));
        b.put("itemScope", a.get("itemScope").textValue());
        assertNotEquals(ReviewConfirmationBinding.prepare(a, context, clock).snapshot().digest(),
                ReviewConfirmationBinding.prepare(b, context, clock).snapshot().digest());
    }
}
