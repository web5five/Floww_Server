package com.floww.server.aiproposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.floww.server.integration.kiln.KilnClient;
import com.floww.server.aiproposal.MerchantProposal.Asset;
import com.floww.server.aiproposal.MerchantProposal.Context;
import com.floww.server.aiproposal.MerchantProposal.Finding;
import com.floww.server.aiproposal.MerchantProposal.Pair;
import com.floww.server.aiproposal.MerchantProposal.Quote;
import com.floww.server.aiproposal.MerchantProposal.Result;
import com.floww.server.aiproposal.MerchantProposal.Status;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Executable F013 handoff examples. The fake implements only the KilnClient boundary. */
class MerchantProposalExamplesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FixtureKilnClient extends KilnClient {
        private final MutableClock clock;
        private final JsonNode arguments;
        private final long advanceSeconds;
        private int calls;
        private JsonNode sent;
        private Instant limit;

        FixtureKilnClient(MutableClock clock, JsonNode arguments, long advanceSeconds) {
            super(JSON, "http://127.0.0.1:1/v1", "synthetic-fixture-key");
            this.clock = clock;
            this.arguments = arguments;
            this.advanceSeconds = advanceSeconds;
        }

        @Override public KilnClient.Result next(List<Map<String, Object>> messages, List<String> allowedTools, Instant deadline) {
            calls++;
            assertEquals(List.of("propose_purchase"), allowedTools);
            assertEquals("system", messages.get(0).get("role"));
            assertEquals("user", messages.get(1).get("role"));
            try { sent = JSON.readTree((String) messages.get(1).get("content")); }
            catch (Exception e) { throw new AssertionError("fixture payload is JSON", e); }
            limit = deadline;
            clock.advanceSeconds(advanceSeconds);
            try {
                String raw = JSON.writeValueAsString(arguments);
                return new KilnClient.Result(KilnClient.MODEL, "tool_calls", "synthetic-call", "propose_purchase",
                        raw, arguments, Map.of(), "unknown", null, null, 1);
            } catch (Exception e) { throw new AssertionError("fixture arguments are JSON", e); }
        }
    }

    @TestFactory Stream<DynamicTest> checkedInExamples() throws Exception {
        JsonNode fixture;
        try (InputStream stream = getClass().getResourceAsStream("/aiproposal/merchant-proposal-examples.json")) {
            assertNotNull(stream);
            fixture = JSON.readTree(stream);
        }
        assertEquals("f013-local-fixture-v1", fixture.path("schema").asText());
        assertEquals(12, fixture.path("cases").size());
        List<DynamicTest> cases = new ArrayList<>();
        for (JsonNode testCase : fixture.path("cases")) {
            cases.add(DynamicTest.dynamicTest(testCase.path("id").asText(), () -> runCase(fixture, testCase)));
        }
        return cases.stream();
    }

    private static void runCase(JsonNode fixture, JsonNode testCase) throws Exception {
        Instant fixed = Instant.parse(fixture.path("clock").asText());
        MutableClock clock = new MutableClock(fixed);
        ObjectNode contextJson = fixture.path("input").path("context").deepCopy();
        merge(contextJson, testCase.path("contextChanges"));
        Context context = context(contextJson);
        Map<String, Quote> sourceQuotes = new LinkedHashMap<>();
        for (JsonNode quoteJson : fixture.path("input").path("quotes")) {
            ObjectNode changed = quoteJson.deepCopy();
            merge(changed, testCase.path("quoteChanges").path(quoteJson.path("quoteId").asText()));
            Quote quote = quote(changed);
            sourceQuotes.put(quote.quoteId(), quote);
        }
        List<Quote> quotes = new ArrayList<>();
        if (testCase.path("quoteIds").isArray()) {
            for (JsonNode id : testCase.path("quoteIds")) quotes.add(sourceQuotes.get(id.asText()));
        } else quotes.addAll(sourceQuotes.values());
        List<Quote> originalQuotes = List.copyOf(quotes);
        String originalContext = context.toString();
        List<String> originalQuoteValues = quotes.stream().map(Quote::toString).toList();
        JsonNode expected = testCase.path("expected");
        FixtureKilnClient client = new FixtureKilnClient(clock, testCase.path("modelArguments"),
                testCase.path("advanceClockSecondsOnModelCall").asLong(0));
        Instant wallBefore = Instant.now();
        Result actual = new AiMerchantProposal(client, clock).propose(context, quotes);
        Instant wallAfter = Instant.now();

        assertEquals(Status.valueOf(expected.path("status").asText()), actual.status());
        assertEquals(nullableText(expected.path("reason")), actual.reason());
        assertEquals(context.taskRef(), actual.taskRef());
        assertEquals(context.mandateRef(), actual.mandateRef());
        assertEquals(context.mandateRevision(), actual.mandateRevision());
        assertEquals(expected.path("modelCalls").asInt(), client.calls);
        assertEquals(originalContext, context.toString(), "input context must remain unchanged");
        assertEquals(originalQuotes, quotes, "input quote list must remain unchanged");
        assertEquals(originalQuoteValues, quotes.stream().map(Quote::toString).toList(), "quote values must remain unchanged");

        String selectedId = nullableText(expected.path("selectedQuoteId"));
        if (selectedId == null) assertNull(actual.proposedQuote(), "no substitute or payment proof");
        else {
            assertSame(sourceQuotes.get(selectedId), actual.proposedQuote(), "exact caller quote binding");
            assertEquals(sourceQuotes.get(selectedId).totalBaseUnits(), actual.proposedQuote().totalBaseUnits());
            assertEquals("PROPOSED", actual.status().name());
        }
        Map<String, List<String>> expectedFindings = new LinkedHashMap<>();
        expected.path("findings").fields().forEachRemaining(e -> {
            List<String> reasons = new ArrayList<>();
            e.getValue().forEach(value -> reasons.add(value.asText()));
            expectedFindings.put(e.getKey(), reasons);
        });
        Map<String, List<String>> actualFindings = new LinkedHashMap<>();
        for (Finding finding : actual.findings()) actualFindings.put(finding.quoteId(), finding.reasons());
        assertEquals(expectedFindings, actualFindings);

        if (client.calls == 0) assertNull(actual.provenance());
        else {
            assertNotNull(actual.provenance());
            assertEquals("local_model_fixture", actual.provenance().modelEvidenceMode());
            assertEquals(1, actual.provenance().attempts());
            assertEquals(context.taskRef(), client.sent.path("taskRef").asText());
            assertEquals(context.mandateRef(), client.sent.path("mandateRef").asText());
            assertEquals(context.mandateRevision(), client.sent.path("mandateRevision").asText());
            assertEquals(context.maximumTotalBaseUnits(), client.sent.path("maximumTotalBaseUnits").asText());
            List<String> sentIds = new ArrayList<>();
            for (JsonNode sentQuote : client.sent.path("eligibleQuotes")) {
                String id = sentQuote.path("quoteId").asText();
                sentIds.add(id);
                Quote original = sourceQuotes.get(id);
                assertNotNull(original);
                assertEquals(original.totalBaseUnits(), sentQuote.path("totalBaseUnits").asText());
                assertEquals(original.recipient(), sentQuote.path("recipient").asText());
            }
            List<String> expectedEligible = expectedFindings.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
            assertEquals(expectedEligible, sentIds);
            // The fixture states the expected budget independently of the production calculation.
            long expectedSeconds = expected.path("providerBudgetSeconds").asLong(-1);
            assertTrue(expectedSeconds > 0);
            assertFalse(client.limit.isBefore(wallBefore.plusSeconds(expectedSeconds)));
            assertFalse(client.limit.isAfter(wallAfter.plusSeconds(expectedSeconds)));
        }
    }

    private static void merge(ObjectNode target, JsonNode changes) {
        if (changes.isObject()) changes.fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue()));
    }
    private static String nullableText(JsonNode value) { return value.isNull() || value.isMissingNode() ? null : value.asText(); }
    private static Instant instant(JsonNode value) { return value.isNull() ? null : Instant.parse(value.asText()); }
    private static Boolean nullableBoolean(JsonNode value) { return value.isNull() ? null : value.booleanValue(); }
    private static Asset asset(JsonNode value) {
        return new Asset(value.path("chainId").asText(), value.path("tokenAddress").asText(), value.path("decimals").asInt());
    }
    private static Context context(JsonNode value) {
        List<Pair> pairs = new ArrayList<>();
        for (JsonNode pair : value.path("permittedPairs"))
            pairs.add(new Pair(pair.path("merchantId").asText(), pair.path("recipient").asText()));
        return new Context(value.path("taskRef").asText(), value.path("mandateRef").asText(),
                value.path("mandateRevision").asText(), value.path("itemId").asText(),
                nullableText(value.path("maximumTotalBaseUnits")), asset(value.path("asset")),
                instant(value.path("deadline")), pairs, instant(value.path("requiredFulfillmentBy")),
                nullableBoolean(value.path("prescriptionEligible")), nullableBoolean(value.path("identityEligible")),
                value.path("mandateState").asText());
    }
    private static Quote quote(JsonNode value) {
        return new Quote(value.path("quoteId").asText(), value.path("merchantId").asText(),
                value.path("recipient").asText(), value.path("itemId").asText(), asset(value.path("asset")),
                value.path("totalBaseUnits").asText(), instant(value.path("expiresAt")),
                value.path("inStock").asBoolean(), instant(value.path("promisedFulfillmentAt")),
                value.path("prescriptionRequired").asBoolean(), value.path("identityRequired").asBoolean());
    }
}
