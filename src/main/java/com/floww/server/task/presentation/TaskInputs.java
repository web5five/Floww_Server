package com.floww.server.task.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.task.application.TaskCommands.AttemptInput;
import com.floww.server.task.application.TaskCommands.ConfirmInput;
import com.floww.server.task.application.TaskCommands.MandateInput;
import com.floww.server.task.application.TaskCommands.RevisionInput;
import com.floww.server.task.domain.BaseUnits;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /api/v1/tasks 요청 검증 — Issue #34.
 *
 * <p>알 수 없는 필드(예: {@code confirmed}, {@code ownerId}, {@code role})는 400 INVALID_INPUT이다.
 * 사용자 승인은 요청 boolean이 아니라 EIP-712 서명으로만 증명한다 (#16 6번, 정책과 결정 3장).
 * 금액은 JSON 문자열의 base unit 정수만 받는다. JSON 숫자·소수·부호·공백은 거절한다.
 */
public final class TaskInputs {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern NONCE = Pattern.compile("0x[0-9a-f]{64}");
    private static final Set<String> MANDATE_REQUIRED = Set.of("goal", "itemId", "maxAmountBaseUnits", "expiresAt");
    private static final Set<String> MANDATE_OPTIONAL = Set.of("allowedMerchantIds");
    /** 위임 기한은 최대 30일. 더 긴 기한은 입력 실수로 본다. */
    static final long MAX_MANDATE_DAYS = 30;

    private TaskInputs() { }

    public static String idempotency(String value) {
        if (value == null || !IDEMPOTENCY.matcher(value).matches()) throw invalid();
        return value;
    }

    public static MandateInput mandate(JsonNode root, int decimals, Instant now) {
        fields(root, MANDATE_REQUIRED, MANDATE_OPTIONAL);
        return mandateFields(root, decimals, now);
    }

    public static RevisionInput revision(JsonNode root, int decimals, Instant now) {
        Set<String> required = new HashSet<>(MANDATE_REQUIRED);
        required.add("baseVersion");
        fields(root, required, MANDATE_OPTIONAL);
        JsonNode base = root.path("baseVersion");
        if (!base.isInt() || base.intValue() <= 0) throw invalid();
        return new RevisionInput(base.intValue(), mandateFields(root, decimals, now));
    }

    public static AttemptInput attempt(JsonNode root) {
        fields(root, Set.of("quoteId", "proposedBy"), Set.of("recipientAddress"));
        String quoteId = text(root.path("quoteId"));
        String proposedBy = text(root.path("proposedBy"));
        if (!ID.matcher(quoteId).matches() || !Set.of("AI", "USER").contains(proposedBy)) throw invalid();
        String recipient = null;
        if (root.has("recipientAddress")) {
            // 형식이 틀린 주소도 "레지스트리와 다른 수취인"으로 DENY 기록할 수 있게 길이만 제한한다.
            recipient = text(root.path("recipientAddress")).trim().toLowerCase(Locale.ROOT);
            if (recipient.isEmpty() || recipient.length() > 128) throw invalid();
        }
        return new AttemptInput(quoteId, proposedBy, recipient);
    }

    public static ConfirmInput confirm(JsonNode root) {
        fields(root, Set.of("mandateId", "version", "attemptId", "nonce", "signature"), Set.of());
        JsonNode version = root.path("version");
        String nonce = text(root.path("nonce")).toLowerCase(Locale.ROOT);
        String signature = text(root.path("signature"));
        if (!version.isInt() || version.intValue() <= 0 || !NONCE.matcher(nonce).matches()
                || signature.length() != 132) throw invalid();
        return new ConfirmInput(uuid(root.path("mandateId")), version.intValue(), uuid(root.path("attemptId")),
                nonce, signature);
    }

    public static UUID order(JsonNode root) {
        fields(root, Set.of("attemptId"), Set.of());
        return uuid(root.path("attemptId"));
    }

    /** 본문 없음 또는 {"reason": "..."}만 허용. reason은 기록하지 않는다(자유 텍스트 개인정보 방지). */
    public static void optionalReason(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) return;
        fields(root, Set.of(), Set.of("reason"));
        if (root.has("reason")) {
            String reason = text(root.path("reason"));
            if (reason.length() > 64) throw invalid();
        }
    }

    public static int limit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_LIMIT);
        return limit;
    }

    public static long cursor(long after) {
        if (after < 0) throw new ApiException(ErrorCode.INVALID_CURSOR);
        return after;
    }

    public static UUID owner(String owner) {
        if (owner == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException devProfileOwner) {
            // dev 프로필의 alice/bob 토큰은 users 행이 없어 Task를 가질 수 없다. JWT로 로그인해야 한다.
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static MandateInput mandateFields(JsonNode root, int decimals, Instant now) {
        String goal = text(root.path("goal")).trim();
        String itemId = text(root.path("itemId"));
        if (goal.isBlank() || goal.length() > 500 || goal.chars().anyMatch(Character::isISOControl)
                || !ID.matcher(itemId).matches()) throw invalid();
        BigInteger max = BaseUnits.parsePositive(text(root.path("maxAmountBaseUnits")), decimals)
                .orElseThrow(TaskInputs::invalid);
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(text(root.path("expiresAt")));
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(java.time.Duration.ofDays(MAX_MANDATE_DAYS)))) {
            throw invalid();
        }
        List<String> merchants = null;
        if (root.has("allowedMerchantIds")) {
            JsonNode list = root.path("allowedMerchantIds");
            if (!list.isArray() || list.isEmpty() || list.size() > 16) throw invalid();
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode node : list) {
                String id = text(node);
                if (!ID.matcher(id).matches() || !unique.add(id)) throw invalid();
            }
            merchants = new ArrayList<>(unique);
        }
        return new MandateInput(goal, itemId, max, expiresAt, merchants);
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> seen = new HashSet<>();
        node.fieldNames().forEachRemaining(seen::add);
        if (!seen.containsAll(required)) throw invalid();
        for (String name : seen) {
            if (!required.contains(name) && !optional.contains(name)) throw invalid();
        }
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static UUID uuid(JsonNode node) {
        try {
            return UUID.fromString(text(node));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_INPUT);
    }
}
