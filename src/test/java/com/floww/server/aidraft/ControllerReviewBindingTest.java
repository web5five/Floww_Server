package com.floww.server.aidraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent controller counterexamples for F009; no authenticated user/payment is exercised. */
class ControllerReviewBindingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2030-09-29T03:00:00Z");
    private static final Clock NOW = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
    private static final ReviewConfirmationBinding.Context CONTEXT =
            new ReviewConfirmationBinding.Context("owner-a", "task-a", 3, START);

    private ObjectNode draft() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"schemaVersion":"ai-draft.v1","objective":"Obtain a report",
             "itemScope":"Public CSV quality report","providerCriteria":"Registered report provider",
             "maximumTotalCost":{"amount":"10.00","asset":"TEST_USDC","includesAllUserPaidFees":true},
             "deadline":"2030-09-29T04:00:00Z","fulfillmentCriterion":"Receive a readable quality report"}
            """);
    }
    private ReviewConfirmationBinding.Snapshot snapshot(ObjectNode draft) {
        var prepared = ReviewConfirmationBinding.prepare(draft, CONTEXT, NOW);
        assertTrue(prepared.ready(), prepared.reasonCode());
        return prepared.snapshot();
    }
    private ReviewConfirmationBinding.Receipt receipt(ReviewConfirmationBinding.Snapshot snapshot) {
        return new ReviewConfirmationBinding.Receipt("owner-a", "task-a", 3,
                snapshot.digest(), true, START.plusSeconds(20));
    }
    private void rejected(ReviewConfirmationBinding.Verdict verdict) {
        assertEquals("CONFIRMATION_REJECTED", verdict.status());
        assertNotNull(verdict.reasonCode());
    }

    @Test void everyDisplayedTermInvalidatesPriorConfirmation() throws Exception {
        ObjectNode original = draft();
        var s = snapshot(original);
        for (String field : new String[]{"objective", "itemScope", "providerCriteria", "fulfillmentCriterion"}) {
            ObjectNode changed = original.deepCopy();
            changed.put(field, original.get(field).asText() + " revised");
            rejected(ReviewConfirmationBinding.verify(s, changed, CONTEXT, receipt(s), NOW));
        }
        for (String amount : new String[]{"10", "10.0", "10.01", "9.99"}) {
            ObjectNode changed = original.deepCopy();
            ((ObjectNode) changed.get("maximumTotalCost")).put("amount", amount);
            rejected(ReviewConfirmationBinding.verify(s, changed, CONTEXT, receipt(s), NOW));
        }
        ObjectNode changed = original.deepCopy();
        changed.put("deadline", "2030-09-29T13:00:00+09:00"); // Same instant, different reviewed text.
        rejected(ReviewConfirmationBinding.verify(s, changed, CONTEXT, receipt(s), NOW));
        changed = original.deepCopy();
        ((ObjectNode) changed.get("maximumTotalCost")).put("asset", "USDC");
        rejected(ReviewConfirmationBinding.verify(s, changed, CONTEXT, receipt(s), NOW));
        changed = original.deepCopy();
        ((ObjectNode) changed.get("maximumTotalCost")).put("includesAllUserPaidFees", false);
        rejected(ReviewConfirmationBinding.verify(s, changed, CONTEXT, receipt(s), NOW));
    }

    @Test void canonicalEncodingDoesNotMergeUnicodeOrFieldBoundaries() throws Exception {
        ObjectNode a = draft(), b = draft();
        a.put("objective", "ab"); a.put("itemScope", "c");
        b.put("objective", "a"); b.put("itemScope", "bc");
        assertNotEquals(snapshot(a).digest(), snapshot(b).digest());
        a.put("objective", "caf\u00e9"); b.put("objective", "cafe\u0301");
        assertNotEquals(snapshot(a).digest(), snapshot(b).digest());
        a.put("objective", "\ud800"); b.put("objective", "\ud801");
        var first = ReviewConfirmationBinding.prepare(a, CONTEXT, NOW);
        var second = ReviewConfirmationBinding.prepare(b, CONTEXT, NOW);
        if (first.ready() && second.ready()) assertNotEquals(first.snapshot().digest(), second.snapshot().digest());
        // Either reject malformed UTF-16, or preserve its exact code units; never collapse to UTF-8 replacement.
        a.put("objective", "Symbol \ud83d\ude80"); b.put("objective", "Symbol ?");
        assertNotEquals(snapshot(a).digest(), snapshot(b).digest());
    }

    @Test void persistedReviewRestoresButCannotMoveToAnotherContext() throws Exception {
        ObjectNode original = draft(); var s = snapshot(original);
        ObjectNode reordered = JSON.createObjectNode();
        original.properties().stream().sorted((a,b) -> b.getKey().compareTo(a.getKey()))
                .forEach(e -> reordered.set(e.getKey(), e.getValue()));
        var restored = ReviewConfirmationBinding.prepare(reordered, CONTEXT,
                Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC)).snapshot();
        assertEquals(s.digest(), restored.digest());
        assertEquals("CONFIRMATION_MATCHED", ReviewConfirmationBinding.verify(restored, original,
                CONTEXT, receipt(s), NOW).status());
        for (var changed : new ReviewConfirmationBinding.Context[]{
                new ReviewConfirmationBinding.Context("owner-b", "task-a", 3, START),
                new ReviewConfirmationBinding.Context("owner-a", "task-b", 3, START),
                new ReviewConfirmationBinding.Context("owner-a", "task-a", 4, START),
                new ReviewConfirmationBinding.Context("owner-a", "task-a", 3, START.plusNanos(1))}) {
            rejected(ReviewConfirmationBinding.verify(s, original, changed, receipt(s), NOW));
            assertNotEquals(s.digest(), ReviewConfirmationBinding.prepare(original, changed, NOW).snapshot().digest());
        }
    }

    @Test void approvalCannotSurviveExpiryOrInvalidConfirmationTime() throws Exception {
        ObjectNode original = draft(); var s = snapshot(original);
        Clock expiry = Clock.fixed(Instant.parse("2030-09-29T04:00:00Z"), ZoneOffset.UTC);
        rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, receipt(s), expiry));
        for (Instant time : new Instant[]{START.minusNanos(1), NOW.instant().plusNanos(1), null}) {
            var bad = new ReviewConfirmationBinding.Receipt("owner-a", "task-a", 3, s.digest(), true, time);
            rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, bad, NOW));
        }
    }

    @Test void mutatedJsonDoesNotAlterPersistedSnapshot() throws Exception {
        ObjectNode original = draft(), current = original.deepCopy(); var s = snapshot(original);
        String digest = s.digest();
        ((ObjectNode) original.get("maximumTotalCost")).put("amount", "1000");
        ((ObjectNode) s.draft().get("maximumTotalCost")).put("amount", "5000");
        assertEquals("10.00", s.draft().path("maximumTotalCost").path("amount").textValue());
        assertEquals(digest, s.digest());
        assertEquals("CONFIRMATION_MATCHED", ReviewConfirmationBinding.verify(s, current, CONTEXT, receipt(s), NOW).status());
        rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, receipt(s), NOW));
    }

    @Test void callerConfirmationFieldsNeverPromoteAnAiDraft() throws Exception {
        ObjectNode original = draft(); var s = snapshot(original);
        rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, null, NOW));
        var withdrawn = new ReviewConfirmationBinding.Receipt("owner-a", "task-a", 3, s.digest(), false, START.plusSeconds(20));
        rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, withdrawn, NOW));
        original.put("confirmed", true);
        assertFalse(ReviewConfirmationBinding.prepare(original, CONTEXT, NOW).ready());
        rejected(ReviewConfirmationBinding.verify(s, original, CONTEXT, receipt(s), NOW));
    }
}
