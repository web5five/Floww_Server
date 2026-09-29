package com.floww.server.aiproposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.integration.kiln.KilnClient;
import com.floww.server.aiproposal.MerchantProposal.Asset;
import com.floww.server.aiproposal.MerchantProposal.Context;
import com.floww.server.aiproposal.MerchantProposal.Pair;
import com.floww.server.aiproposal.MerchantProposal.Quote;
import com.floww.server.aiproposal.MerchantProposal.Result;
import com.floww.server.aiproposal.MerchantProposal.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiMerchantProposalFreshnessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Asset ASSET = new Asset("synthetic-chain", "synthetic-token", 6);

    /** Work between the initial precheck and inference advances logical time without sleeping. */
    private static final class BoundaryClock extends Clock {
        private final Instant initial;
        private final Instant atInference;
        private boolean initialRead;
        private Instant current;
        BoundaryClock(Instant initial, Instant atInference) {
            this.initial = initial;
            this.atInference = atInference;
            current = initial;
        }
        void afterProvider(Instant instant) { current = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            if (!initialRead) { initialRead = true; return initial; }
            if (current.equals(initial)) current = atInference;
            return current;
        }
    }

    private static final class CaptureKiln extends KilnClient {
        private final BoundaryClock clock;
        private final String selectedId;
        private Instant afterCall;
        private int calls;
        private Instant limit;
        private JsonNode payload;
        CaptureKiln(BoundaryClock clock, String selectedId) {
            super(JSON, "http://127.0.0.1:1/v1", "synthetic-fixture-key");
            this.clock = clock;
            this.selectedId = selectedId;
        }
        @Override public KilnClient.Result next(List<Map<String, Object>> messages, List<String> allowedTools, Instant deadline) {
            calls++;
            limit = deadline;
            assertEquals(List.of("propose_purchase"), allowedTools);
            try { payload = JSON.readTree((String) messages.get(1).get("content")); }
            catch (Exception e) { throw new AssertionError(e); }
            if (afterCall != null) clock.afterProvider(afterCall);
            String raw = "{\"quoteId\":\"" + selectedId + "\"}";
            return new KilnClient.Result(KilnClient.MODEL, "tool_calls", "fixture-call", "propose_purchase",
                    raw, null, Map.of(), "unknown", null, null, 1);
        }
    }

    private static Context context(Instant mandate, Instant fulfillment) {
        return new Context("task", "mandate", "rev", "item", "100", ASSET, mandate,
                List.of(new Pair("A", "recipient-A"), new Pair("B", "recipient-B")),
                fulfillment, false, false, "ACTIVE");
    }
    private static Quote quote(String id, String merchant, Instant expiry, Instant promise) {
        return new Quote(id, merchant, "recipient-" + merchant, "item", ASSET, "50", expiry,
                true, promise, false, false);
    }
    private static List<String> sentIds(CaptureKiln kiln) {
        List<String> ids = new ArrayList<>();
        for (JsonNode quote : kiln.payload.path("eligibleQuotes")) ids.add(quote.path("quoteId").asText());
        return ids;
    }
    private static void deadlineAbout(Instant before, Instant after, CaptureKiln kiln, long seconds) {
        assertNotNull(kiln.limit);
        assertFalse(kiln.limit.isBefore(before.plusSeconds(seconds)));
        assertFalse(kiln.limit.isAfter(after.plusSeconds(seconds)));
    }

    @Test void mandateAndRequiredFulfillmentExpiringBeforeInferenceMakeNoCall() {
        for (boolean mandateFirst : List.of(true, false)) {
            Instant mandate = START.plusSeconds(mandateFirst ? 5 : 20);
            Instant fulfillment = START.plusSeconds(mandateFirst ? 5 : 4);
            BoundaryClock clock = new BoundaryClock(START, START.plusSeconds(5));
            CaptureKiln kiln = new CaptureKiln(clock, "a");
            Result result = new AiMerchantProposal(kiln, clock).propose(context(mandate, fulfillment),
                    List.of(quote("a", "A", START.plusSeconds(30), START.plusSeconds(3))));
            assertEquals(Status.REJECTED, result.status());
            assertEquals(0, kiln.calls);
        }
    }

    @Test void allCandidateWindowsExpiredAtInferenceMakeNoCall() {
        BoundaryClock clock = new BoundaryClock(START, START.plusSeconds(6));
        CaptureKiln kiln = new CaptureKiln(clock, "a");
        Result result = new AiMerchantProposal(kiln, clock).propose(
                context(START.plusSeconds(60), START.plusSeconds(50)),
                List.of(quote("a", "A", START.plusSeconds(6), START.plusSeconds(30)),
                        quote("b", "B", START.plusSeconds(30), START.plusSeconds(5))));
        assertEquals(Status.NO_CANDIDATE, result.status());
        assertEquals(0, kiln.calls);
    }

    @Test void expiredQuoteIsRemovedButLongerAlternativeKeepsUsefulTime() {
        BoundaryClock clock = new BoundaryClock(START, START.plusSeconds(6));
        CaptureKiln kiln = new CaptureKiln(clock, "b");
        Instant before = Instant.now();
        Result result = new AiMerchantProposal(kiln, clock).propose(
                context(START.plusSeconds(100), START.plusSeconds(80)),
                List.of(quote("a", "A", START.plusSeconds(5), START.plusSeconds(70)),
                        quote("b", "B", START.plusSeconds(36), START.plusSeconds(70))));
        Instant after = Instant.now();
        assertEquals(Status.PROPOSED, result.status());
        assertEquals(List.of("b"), sentIds(kiln));
        assertEquals(1, kiln.calls);
        deadlineAbout(before, after, kiln, 30);
    }

    @Test void freshDeadlineUsesEarliestCommonAndLatestLiveCandidateBoundary() {
        for (int i = 0; i < 3; i++) {
            Instant mandate = START.plusSeconds(i == 0 ? 26 : 100);
            Instant fulfillment = START.plusSeconds(i == 0 ? 26 : i == 1 ? 24 : 80);
            Instant expiry = START.plusSeconds(i == 2 ? 22 : 70);
            Instant promise = START.plusSeconds(i == 0 ? 26 : i == 1 ? 24 : 70);
            BoundaryClock clock = new BoundaryClock(START, START.plusSeconds(7));
            CaptureKiln kiln = new CaptureKiln(clock, "a");
            Instant before = Instant.now();
            Result result = new AiMerchantProposal(kiln, clock).propose(context(mandate, fulfillment),
                    List.of(quote("a", "A", expiry, promise)));
            Instant after = Instant.now();
            assertEquals(Status.PROPOSED, result.status());
            deadlineAbout(before, after, kiln, i == 0 ? 19 : i == 1 ? 17 : 15);
        }
    }

    @Test void exactBoundaryExpiresAndLateProviderResultIsRejected() {
        BoundaryClock clock = new BoundaryClock(START, START.plusSeconds(2));
        CaptureKiln kiln = new CaptureKiln(clock, "a");
        Result expired = new AiMerchantProposal(kiln, clock).propose(context(START.plusSeconds(30), START.plusSeconds(20)),
                List.of(quote("a", "A", START.plusSeconds(2), START.plusSeconds(10))));
        assertEquals(Status.NO_CANDIDATE, expired.status());
        assertEquals(0, kiln.calls);

        BoundaryClock lateClock = new BoundaryClock(START, START);
        CaptureKiln lateKiln = new CaptureKiln(lateClock, "a");
        lateKiln.afterCall = START.plusSeconds(10);
        Result late = new AiMerchantProposal(lateKiln, lateClock).propose(context(START.plusSeconds(30), START.plusSeconds(20)),
                List.of(quote("a", "A", START.plusSeconds(10), START.plusSeconds(15))));
        assertEquals(Status.REJECTED, late.status());
        assertEquals("EXPIRED_DURING_PROPOSAL", late.reason());
        assertEquals(1, lateKiln.calls);
    }
}
