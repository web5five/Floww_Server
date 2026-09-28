package com.floww.server;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ExecutionService {
    public static final int MAX_MODEL_CALLS = 4;
    public static final int MAX_TOOL_CALLS = 3;
    public static final Duration MAX_RUN_TIME = Duration.ofSeconds(120);
    private final ExecutionStore store;
    private final KilnClient kiln;
    private final MerchantGateway merchant;

    public ExecutionService(ExecutionStore store, KilnClient kiln, MerchantGateway merchant) {
        this.store = store;
        this.kiln = kiln;
        this.merchant = merchant;
    }

    public ExecutionStore.Execution run(String owner, UUID id) {
        ExecutionStore.Execution execution = store.get(owner, id);
        store.claim(owner, id, merchant.available() ? merchant.evidenceMode() : "merchant_unavailable");
        if (!merchant.available()) return fail(owner, id, "MERCHANT_NOT_CONFIGURED", 0);
        if (!execution.mandate().expiresAt().isAfter(Instant.now()))
            return reject(owner, id, "MANDATE_EXPIRED");
        Instant deadline = Instant.now().plus(MAX_RUN_TIME);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "Use the available merchant tools in order: search_offers, get_quote, propose_purchase. Use exact IDs returned by tools. You may only propose local policy review. Do not claim payment, signing, or fulfillment. After proposal tool result, stop."));
        Inputs.Mandate mandate = execution.mandate();
        messages.add(Map.of("role", "user", "content", "Goal: " + mandate.goal()
                + "; itemId: " + mandate.itemId() + "; maximum full cost: "
                + mandate.maxTotal().toPlainString() + " " + mandate.currency()
                + "; recipient: " + mandate.recipient() + "; mandate expires: "
                + mandate.expiresAt() + ". Find an offer and quote, then propose its quote ID."));
        Set<String> ids = new HashSet<>();
        Set<String> offers = new HashSet<>();
        String proposedQuoteId = null;
        String[] sequence = {"search_offers", "get_quote", "propose_purchase"};
        for (int turn = 0; turn < MAX_MODEL_CALLS; turn++) {
            if (!Instant.now().isBefore(deadline)) return fail(owner, id, "RUN_TIME_LIMIT", 0);
            if (!mandate.expiresAt().isAfter(Instant.now())) return reject(owner, id, "MANDATE_EXPIRED");
            KilnClient.Result result;
            try { result = kiln.next(messages, turn < MAX_TOOL_CALLS ? List.of(sequence[turn]) : List.of(), deadline); }
            catch (KilnClient.Failure e) {
                for (int attempt = 1; attempt < e.attempts(); attempt++)
                    recordProvider(owner, id, null, turn + 1, attempt, "unknown_retry");
                if (e.partial() != null)
                    recordProvider(owner, id, e.partial(), turn + 1, e.attempts(), e.code());
                else if (e.attempts() > 0)
                    recordProvider(owner, id, null, turn + 1, e.attempts(), e.code());
                return fail(owner, id, e.code(), e.attempts());
            }
            catch (RuntimeException e) { return fail(owner, id, "MODEL_CALL_FAILED", 0); }
            for (int attempt = 1; attempt < result.attempts(); attempt++)
                recordProvider(owner, id, null, turn + 1, attempt, "unknown_retry");
            recordProvider(owner, id, result, turn + 1, result.attempts(), "parsed");
            if (turn < MAX_TOOL_CALLS && !Instant.now().isBefore(deadline))
                return fail(owner, id, "RUN_TIME_LIMIT", result.attempts());
            if (!KilnClient.MODEL.equals(result.modelId())) return reject(owner, id, "MODEL_MISMATCH");
            if (turn == MAX_TOOL_CALLS) {
                if (!"stop".equals(result.finishReason())) return reject(owner, id, "TOOL_LOOP_LIMIT");
                if (!mandate.expiresAt().isAfter(Instant.now())) return reject(owner, id, "MANDATE_EXPIRED");
                MerchantGateway.Quote finalQuote = proposedQuoteId == null
                        ? null : store.quote(owner, id, proposedQuoteId);
                if (finalQuote == null) return reject(owner, id, "UNKNOWN_QUOTE_ID");
                String finalPolicy = policy(mandate, finalQuote);
                if (finalPolicy != null) return reject(owner, id, finalPolicy);
                if (!Instant.now().isBefore(deadline)) return fail(owner, id, "RUN_TIME_LIMIT", result.attempts());
                boolean reviewed = store.finishReviewed(owner, id, proposedQuoteId, deadline, Map.of(
                        "paymentStatus", "NOT_AVAILABLE", "quoteStatus", "TEST_FIXTURE_ONLY",
                        "modelCalls", MAX_MODEL_CALLS, "toolCalls", MAX_TOOL_CALLS), merchant.evidenceMode());
                if (!reviewed) {
                    if (!mandate.expiresAt().isAfter(Instant.now())) return reject(owner, id, "MANDATE_EXPIRED");
                    MerchantGateway.Quote changed = store.quote(owner, id, proposedQuoteId);
                    if (changed == null) return reject(owner, id, "UNKNOWN_QUOTE_ID");
                    String changedPolicy = policy(mandate, changed);
                    if (changedPolicy != null) return reject(owner, id, changedPolicy);
                    return fail(owner, id, "RUN_TIME_LIMIT", result.attempts());
                }
                return store.get(owner, id);
            }
            if (!"tool_calls".equals(result.finishReason())) return reject(owner, id, "EARLY_MODEL_STOP");
            if (!ids.add(result.toolCallId())) return reject(owner, id, "DUPLICATE_TOOL_CALL_ID");
            if (!sequence[turn].equals(result.toolName())) return reject(owner, id, "TOOL_NOT_ALLOWED");
            String argument;
            try { argument = Inputs.argument(result.arguments(), switch (turn) {
                case 0 -> "itemId"; case 1 -> "offerId"; default -> "quoteId";
            }); } catch (ApiException | NullPointerException e) { return reject(owner, id, "INVALID_TOOL_ARGUMENTS"); }
            String safeField = switch (turn) { case 0 -> "itemId"; case 1 -> "offerId"; default -> "quoteId"; };
            store.traceModel(owner, id, "TOOL_REQUEST", Map.of("name", result.toolName(),
                    "arguments", Map.of(safeField, argument)), result.toolCallId(),
                    merchant.evidenceMode(), kiln.modelEvidenceMode());
            Map<String, Object> toolResult;
            try {
                if (turn == 0) {
                    if (!mandate.itemId().equals(argument)) return reject(owner, id, "ITEM_NOT_ALLOWED");
                    List<MerchantGateway.Offer> found = merchant.searchOffers(argument);
                    found.removeIf(o -> !mandate.itemId().equals(o.itemId()));
                    for (MerchantGateway.Offer offer : found) offers.add(offer.offerId());
                    toolResult = Map.of("offers", found);
                } else if (turn == 1) {
                    if (!offers.contains(argument)) return reject(owner, id, "UNKNOWN_OFFER_ID");
                    MerchantGateway.Quote quote = merchant.getQuote(argument);
                    if (!argument.equals(quote.offerId())) return reject(owner, id, "QUOTE_OFFER_MISMATCH");
                    store.saveQuote(owner, id, quote, merchant.evidenceMode());
                    toolResult = Map.of("quote", Map.of("quoteId", quote.quoteId(),
                            "offerId", quote.offerId(), "itemId", quote.itemId(),
                            "totalCost", quote.totalCost().toPlainString(), "currency", quote.currency(),
                            "recipient", quote.recipient(), "expiresAt", quote.expiresAt().toString()));
                    store.trace(owner, id, "QUOTE_STORED", Map.of("quoteId", quote.quoteId(),
                            "itemId", quote.itemId(), "totalCost", quote.totalCost().toPlainString(),
                            "currency", quote.currency(), "recipient", quote.recipient(),
                            "expiresAt", quote.expiresAt().toString()), "merchant_fixture",
                            result.toolCallId(), merchant.evidenceMode());
                    String code = policy(mandate, quote);
                    if (code != null) return reject(owner, id, code);
                } else {
                    MerchantGateway.Quote quote = store.quote(owner, id, argument);
                    if (quote == null) return reject(owner, id, "UNKNOWN_QUOTE_ID");
                    String code = policy(mandate, quote);
                    if (code != null) return reject(owner, id, code);
                    toolResult = Map.of("acceptedForLocalPrecheck", true, "quoteId", quote.quoteId(),
                            "paymentStatus", "NOT_AVAILABLE");
                    store.trace(owner, id, "PROPOSAL_RECORDED", Map.of("quoteId", quote.quoteId(),
                            "authority", "merchant_fixture_quote_local_precheck"), "server",
                            result.toolCallId(), merchant.evidenceMode());
                    proposedQuoteId = quote.quoteId();
                }
            } catch (MerchantGateway.Failure e) { return fail(owner, id, e.code(), 0); }
              catch (RuntimeException e) { return fail(owner, id, "MERCHANT_INTEGRATION_FAILED", 0); }
            store.trace(owner, id, "TOOL_RESULT", toolResult, "server", result.toolCallId(), merchant.evidenceMode());
            // Preserve the validated original assistant call and its matching tool response for Kiln.
            messages.add(Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                    "id", result.toolCallId(), "type", "function", "function", Map.of(
                            "name", result.toolName(), "arguments", result.rawArguments())))));
            messages.add(Map.of("role", "tool", "tool_call_id", result.toolCallId(),
                    "content", stringify(toolResult)));
        }
        return fail(owner, id, "TOOL_LOOP_LIMIT", 0);
    }

    private static String stringify(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void recordProvider(String owner, UUID id, KilnClient.Result result, int modelCall,
                                int attempt, String parseStatus) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("modelId", result == null ? null : result.modelId());
        event.put("generationId", result == null ? null : result.generationId());
        event.put("toolCallId", result == null ? null : result.toolCallId());
        event.put("finishReason", result == null ? null : result.finishReason());
        event.put("attemptNumber", attempt);
        event.put("usageStatus", result == null ? "unknown" : result.usageStatus());
        event.put("usage", result == null ? Map.of() : result.usage());
        event.put("providerCost", result == null ? null : result.cost());
        event.put("modelCallNumber", modelCall);
        event.put("parseStatus", parseStatus);
        event.put("modelEvidenceMode", kiln.modelEvidenceMode());
        store.traceModel(owner, id, "MODEL_RESPONSE", event,
                result == null ? null : result.toolCallId(), merchant.evidenceMode(),
                kiln.modelEvidenceMode());
    }

    private static String policy(Inputs.Mandate mandate, MerchantGateway.Quote quote) {
        Instant now = Instant.now();
        if (!mandate.expiresAt().isAfter(now)) return "MANDATE_EXPIRED";
        if (!quote.expiresAt().isAfter(now)) return "QUOTE_STALE";
        if (!mandate.itemId().equals(quote.itemId())) return "ITEM_NOT_ALLOWED";
        if (!mandate.currency().equals(quote.currency())) return "CURRENCY_MISMATCH";
        if (!mandate.recipient().equals(quote.recipient())) return "RECIPIENT_NOT_ALLOWED";
        if (quote.totalCost().compareTo(mandate.maxTotal()) > 0) return "BUDGET_EXCEEDED";
        return null;
    }

    private ExecutionStore.Execution reject(String owner, UUID id, String code) {
        store.finish(owner, id, "REJECTED", "POLICY_REJECTED",
                Map.of("code", code, "paymentStatus", "NOT_ATTEMPTED"),
                merchant.available() ? merchant.evidenceMode() : "merchant_unavailable");
        return store.get(owner, id);
    }
    private ExecutionStore.Execution fail(String owner, UUID id, String code, int attempts) {
        store.finish(owner, id, "FAILED", "INFERENCE_FAILED", Map.of("code", code,
                "attempts", attempts, "paymentStatus", "NOT_ATTEMPTED"),
                merchant.available() ? merchant.evidenceMode() : "merchant_unavailable");
        return store.get(owner, id);
    }
}
