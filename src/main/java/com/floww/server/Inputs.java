package com.floww.server;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

public final class Inputs {
    private static final Pattern AMOUNT = Pattern.compile("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,8})?");
    private static final Pattern RECIPIENT = Pattern.compile("[A-Za-z0-9._:-]{3,128}");
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern ITEM = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private Inputs() { }

    public record Mandate(String goal, String itemId,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxTotal,
                          String currency, String recipient, Instant expiresAt) { }

    public static Mandate mandate(JsonNode root) {
        fields(root, Set.of("confirmed", "mandate"));
        if (!root.path("confirmed").isBoolean() || !root.path("confirmed").booleanValue()) {
            throw invalid();
        }
        JsonNode node = root.path("mandate");
        fields(node, Set.of("goal", "itemId", "maxTotal", "currency", "recipient", "expiresAt"));
        String goal = node.path("goal").isTextual() ? node.path("goal").textValue().trim() : "";
        String itemId = node.path("itemId").isTextual() ? node.path("itemId").textValue() : "";
        if (goal.isBlank() || goal.length() > 500 || goal.chars().anyMatch(c -> Character.isISOControl(c))
                || !ITEM.matcher(itemId).matches()) throw invalid();
        BigDecimal max = amount(node.path("maxTotal"));
        String currency = currency(node.path("currency"));
        String recipient = recipient(node.path("recipient"));
        Instant expiry = instant(node.path("expiresAt"));
        if (!expiry.isAfter(Instant.now())) throw invalid();
        return new Mandate(goal, itemId, max, currency, recipient, expiry);
    }

    public static String argument(JsonNode node, String field) {
        fields(node, Set.of(field));
        String value = node.path(field).textValue();
        if (value == null || !ITEM.matcher(value).matches()) throw invalid();
        return value;
    }

    public static BigDecimal cost(JsonNode node) { return amount(node); }
    public static String quoteCurrency(JsonNode node) { return currency(node); }
    public static String quoteRecipient(JsonNode node) { return recipient(node); }
    public static Instant quoteExpiry(JsonNode node) { return instant(node); }
    public static String quoteItem(JsonNode node) {
        if (!node.isTextual() || !ITEM.matcher(node.textValue()).matches()) throw invalid();
        return node.textValue();
    }

    public static String idempotency(String value) {
        if (value == null || !IDEMPOTENCY.matcher(value).matches()) throw invalid();
        return value;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.size() != expected.size()) throw invalid();
        Iterator<String> names = node.fieldNames();
        Set<String> actual = new HashSet<>();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }

    private static BigDecimal amount(JsonNode node) {
        if (!node.isTextual() || !AMOUNT.matcher(node.textValue()).matches()) throw invalid();
        BigDecimal value = new BigDecimal(node.textValue());
        if (value.signum() <= 0) throw invalid();
        return value;
    }

    private static String currency(JsonNode node) {
        if (!node.isTextual() || !node.textValue().equals("TEST_USDC")) throw invalid();
        return node.textValue();
    }

    private static String recipient(JsonNode node) {
        if (!node.isTextual() || !RECIPIENT.matcher(node.textValue()).matches()) throw invalid();
        return node.textValue();
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        try { return Instant.parse(node.textValue()); }
        catch (DateTimeParseException e) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_INPUT);
    }
}
