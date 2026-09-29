package com.floww.server.aidraft;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Structural review of an untrusted model proposal. This class grants no authority. */
public final class AiDraftPreflight {
    public static final String SCHEMA_VERSION = "ai-draft.v1";
    private static final Set<String> TOP_FIELDS = Set.of("schemaVersion", "objective", "itemScope",
            "providerCriteria", "maximumTotalCost", "deadline", "fulfillmentCriterion");
    private static final Set<String> COST_FIELDS = Set.of("amount", "asset", "includesAllUserPaidFees");
    private static final Pattern INSTRUCTION_SHAPED = Pattern.compile(
            "(?is)\\b(?:ignore|disregard|override)\\b.{0,64}\\b(?:previous|prior|system|developer)\\b"
                    + ".{0,64}\\b(?:instructions|rules|prompt)\\b"
                    + "|\\b(?:set|mark)\\b.{0,32}\\b(?:approved|authorized|active)\\b.{0,32}\\b(?:true|yes)\\b");

    public record Issue(String code, String field, String question) { }
    public record Result(String status, String schemaVersion, List<Issue> issues, JsonNode draft) {
        public Result { issues = List.copyOf(issues); }
    }

    private AiDraftPreflight() { }

    public static Result evaluate(JsonNode draft, Clock clock) {
        List<Issue> invalid = new ArrayList<>();
        List<Issue> clarify = new ArrayList<>();
        if (draft == null || !draft.isObject()) {
            invalid.add(new Issue("PROPOSAL_OBJECT_REQUIRED", "$", null));
            return result(invalid, clarify, null);
        }
        rejectExtra(draft, TOP_FIELDS, "$", invalid);
        if (!draft.path("schemaVersion").isTextual()
                || !SCHEMA_VERSION.equals(draft.path("schemaVersion").textValue())) {
            invalid.add(new Issue("SCHEMA_VERSION_INVALID", "schemaVersion", null));
        }
        text(draft, "objective", "OBJECTIVE", "What outcome do you want?", invalid, clarify);
        text(draft, "itemScope", "ITEM_SCOPE", "Which exact item or service is in scope?", invalid, clarify);
        text(draft, "providerCriteria", "PROVIDER_CRITERIA",
                "What makes a provider eligible?", invalid, clarify);
        text(draft, "fulfillmentCriterion", "FULFILLMENT_CRITERION",
                "What observable result will count as fulfilled?", invalid, clarify);
        cost(draft.path("maximumTotalCost"), invalid, clarify);
        deadline(draft.path("deadline"), clock, invalid, clarify);
        return result(invalid, clarify, draft);
    }

    private static Result result(List<Issue> invalid, List<Issue> clarify, JsonNode draft) {
        List<Issue> issues = new ArrayList<>(invalid);
        issues.addAll(clarify);
        String status = !invalid.isEmpty() ? "INVALID_PROPOSAL"
                : !clarify.isEmpty() ? "NEEDS_CLARIFICATION" : "READY_FOR_REVIEW";
        return new Result(status, SCHEMA_VERSION, issues, invalid.isEmpty() ? draft.deepCopy() : null);
    }

    private static void rejectExtra(JsonNode object, Set<String> allowed, String path, List<Issue> invalid) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) invalid.add(new Issue("UNKNOWN_FIELD", path + "." + name, null));
        }
    }

    private static void text(JsonNode parent, String field, String code, String question,
                             List<Issue> invalid, List<Issue> clarify) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            clarify.add(new Issue(code + "_MISSING", field, question));
        } else if (!value.isTextual() || value.textValue().length() > 500) {
            invalid.add(new Issue(code + "_INVALID", field, null));
        } else if (INSTRUCTION_SHAPED.matcher(value.textValue()).find()) {
            invalid.add(new Issue("INSTRUCTION_SHAPED_CONTENT", field, null));
        } else if (value.textValue().isBlank() || value.textValue().trim().matches("(?i)(tbd|unknown|unspecified|\\?)")) {
            clarify.add(new Issue(code + "_AMBIGUOUS", field, question));
        }
    }

    private static void cost(JsonNode value, List<Issue> invalid, List<Issue> clarify) {
        String field = "maximumTotalCost";
        if (value.isMissingNode() || value.isNull()) {
            clarify.add(new Issue("COST_MISSING", field,
                    "What is the maximum total you will pay, in which asset, including every fee charged to you?"));
            return;
        }
        if (!value.isObject()) {
            invalid.add(new Issue("COST_INVALID", field, null));
            return;
        }
        rejectExtra(value, COST_FIELDS, field, invalid);
        JsonNode amount = value.path("amount");
        if (amount.isMissingNode() || amount.isNull() || amount.isTextual() && amount.textValue().isBlank()) {
            clarify.add(new Issue("COST_AMOUNT_MISSING", field + ".amount", "What is the exact maximum total amount?"));
        } else if (!amount.isTextual() || !amount.textValue().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,18})?")
                || amount.textValue().length() > 40 || new BigDecimal(amount.textValue()).signum() <= 0) {
            invalid.add(new Issue("COST_AMOUNT_INVALID", field + ".amount", null));
        }
        JsonNode asset = value.path("asset");
        if (asset.isMissingNode() || asset.isNull() || asset.isTextual() && asset.textValue().isBlank()) {
            clarify.add(new Issue("COST_ASSET_MISSING", field + ".asset", "Which asset or currency is this amount in?"));
        } else if (!asset.isTextual() || !asset.textValue().matches("[A-Za-z][A-Za-z0-9_]{1,31}")) {
            invalid.add(new Issue("COST_ASSET_INVALID", field + ".asset", null));
        }
        JsonNode fees = value.path("includesAllUserPaidFees");
        if (fees.isMissingNode() || fees.isNull()) {
            clarify.add(new Issue("COST_FEES_UNRESOLVED", field + ".includesAllUserPaidFees",
                    "Does this maximum include every charge and fee you pay?"));
        } else if (!fees.isBoolean()) {
            invalid.add(new Issue("COST_FEES_INVALID", field + ".includesAllUserPaidFees", null));
        } else if (!fees.booleanValue()) {
            clarify.add(new Issue("COST_FEES_EXCLUDED", field + ".includesAllUserPaidFees",
                    "What is your maximum total including every charge and fee you pay?"));
        }
    }

    private static void deadline(JsonNode value, Clock clock, List<Issue> invalid, List<Issue> clarify) {
        if (value.isMissingNode() || value.isNull() || value.isTextual() && value.textValue().isBlank()) {
            clarify.add(new Issue("DEADLINE_MISSING", "deadline", "What is the exact deadline with date, time and timezone?"));
        } else if (!value.isTextual() || value.textValue().length() > 64) {
            invalid.add(new Issue("DEADLINE_INVALID", "deadline", null));
        } else {
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(value.textValue());
                if (!parsed.toInstant().isAfter(clock.instant()))
                    invalid.add(new Issue("DEADLINE_EXPIRED", "deadline", null));
            } catch (DateTimeParseException e) {
                if (value.textValue().matches("(?i).*(today|tomorrow|tonight|next|이번|오늘|내일).*"))
                    clarify.add(new Issue("DEADLINE_ABSOLUTE_REQUIRED", "deadline",
                            "What is the exact future deadline with timezone?"));
                else invalid.add(new Issue("DEADLINE_INVALID", "deadline", null));
            }
        }
    }
}
