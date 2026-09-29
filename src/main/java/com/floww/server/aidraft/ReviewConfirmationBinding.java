package com.floww.server.aidraft;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** A pure comparison of a reviewed proposal and a receipt supplied by a trusted auth adapter. */
public final class ReviewConfirmationBinding {
    public static final String CONTRACT_VERSION = "review-confirmation.v1";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    public record Context(String ownerId, String taskId, long revision, Instant createdAt) { }

    /** This value must be loaded from trusted core-auth storage; the helper cannot authenticate it. */
    public record Receipt(String ownerId, String taskId, long revision, String digest,
                          Boolean confirmed, Instant confirmedAt) { }

    public static final class Snapshot {
        private final Context context;
        private final JsonNode draft;
        private final String digest;

        private Snapshot(Context context, JsonNode draft, String digest) {
            this.context = context;
            this.draft = draft.deepCopy();
            this.digest = digest;
        }

        public String contractVersion() { return CONTRACT_VERSION; }
        public Context context() { return context; }
        public JsonNode draft() { return draft.deepCopy(); }
        public String digest() { return digest; }
    }

    public record Preparation(Snapshot snapshot, String reasonCode) {
        public boolean ready() { return snapshot != null; }
    }

    public record Verdict(String status, String reasonCode) { }

    private ReviewConfirmationBinding() { }

    public static Preparation prepare(JsonNode proposal, Context context, Clock clock) {
        if (clock == null) return rejectedPreparation("CLOCK_REQUIRED");
        Instant now = clock.instant();
        String contextIssue = contextIssue(context, now);
        if (contextIssue != null) return rejectedPreparation(contextIssue);
        AiDraftPreflight.Result reviewed = AiDraftPreflight.evaluate(proposal, clock);
        if (!"READY_FOR_REVIEW".equals(reviewed.status())) return rejectedPreparation("DRAFT_NOT_READY");
        JsonNode copy = reviewed.draft().deepCopy();
        return new Preparation(new Snapshot(context, copy, digest(context, copy)), null);
    }

    public static Verdict verify(Snapshot snapshot, JsonNode currentDraft, Context currentContext,
                                 Receipt receipt, Clock clock) {
        if (clock == null) return rejected("CLOCK_REQUIRED");
        Instant now = clock.instant();
        if (snapshot == null) return rejected("SNAPSHOT_REQUIRED");
        String snapshotIssue = contextIssue(snapshot.context, now);
        if (snapshotIssue != null) return rejected("SNAPSHOT_" + snapshotIssue);
        String currentIssue = contextIssue(currentContext, now);
        if (currentIssue != null) return rejected("CURRENT_" + currentIssue);
        if (!snapshot.context.ownerId().equals(currentContext.ownerId())) return rejected("OWNER_MISMATCH");
        if (!snapshot.context.taskId().equals(currentContext.taskId())) return rejected("TASK_MISMATCH");
        if (snapshot.context.revision() != currentContext.revision()) return rejected("REVISION_MISMATCH");
        if (!snapshot.context.createdAt().equals(currentContext.createdAt())) return rejected("CREATION_TIME_MISMATCH");

        // Both checks run with the current trusted time; expiry equality is rejected by F008.
        if (!"READY_FOR_REVIEW".equals(AiDraftPreflight.evaluate(snapshot.draft, clock).status()))
            return rejected("SNAPSHOT_DRAFT_NOT_READY");
        AiDraftPreflight.Result current = AiDraftPreflight.evaluate(currentDraft, clock);
        if (!"READY_FOR_REVIEW".equals(current.status())) return rejected("CURRENT_DRAFT_NOT_READY");
        if (!digest(snapshot.context, snapshot.draft).equals(snapshot.digest)) return rejected("SNAPSHOT_DIGEST_INVALID");
        if (!digest(currentContext, current.draft()).equals(snapshot.digest)) return rejected("CURRENT_DRAFT_CHANGED");

        if (receipt == null) return rejected("RECEIPT_REQUIRED");
        if (!validId(receipt.ownerId()) || !validId(receipt.taskId()) || receipt.revision() <= 0
                || receipt.digest() == null || !DIGEST.matcher(receipt.digest()).matches())
            return rejected("RECEIPT_MALFORMED");
        if (!Boolean.TRUE.equals(receipt.confirmed())) return rejected("CONFIRMATION_NOT_ACTIVE");
        if (receipt.confirmedAt() == null || receipt.confirmedAt().isBefore(snapshot.context.createdAt())
                || receipt.confirmedAt().isAfter(now)) return rejected("CONFIRMATION_TIME_INVALID");
        if (!snapshot.context.ownerId().equals(receipt.ownerId())) return rejected("RECEIPT_OWNER_MISMATCH");
        if (!snapshot.context.taskId().equals(receipt.taskId())) return rejected("RECEIPT_TASK_MISMATCH");
        if (snapshot.context.revision() != receipt.revision()) return rejected("RECEIPT_REVISION_MISMATCH");
        if (!snapshot.digest.equals(receipt.digest())) return rejected("RECEIPT_DIGEST_MISMATCH");
        return new Verdict("CONFIRMATION_MATCHED", null);
    }

    private static String contextIssue(Context context, Instant now) {
        if (context == null) return "CONTEXT_REQUIRED";
        if (!validId(context.ownerId())) return "OWNER_ID_INVALID";
        if (!validId(context.taskId())) return "TASK_ID_INVALID";
        if (context.revision() <= 0) return "REVISION_INVALID";
        if (context.createdAt() == null || context.createdAt().isAfter(now)) return "CREATION_TIME_INVALID";
        return null;
    }

    private static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
    private static Preparation rejectedPreparation(String reason) { return new Preparation(null, reason); }
    private static Verdict rejected(String reason) { return new Verdict("CONFIRMATION_REJECTED", reason); }

    /** Private typed, length-delimited encoding; it is neither JCS nor a signature. */
    private static String digest(Context context, JsonNode draft) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeString(out, CONTRACT_VERSION);
            writeString(out, context.ownerId());
            writeString(out, context.taskId());
            out.writeLong(context.revision());
            out.writeLong(context.createdAt().getEpochSecond());
            out.writeInt(context.createdAt().getNano());
            writeNode(out, draft);
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash review snapshot", e);
        }
    }

    private static void writeNode(DataOutputStream out, JsonNode node) throws IOException {
        if (node.isObject()) {
            out.writeByte('O');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            out.writeInt(names.size());
            for (String name : names) {
                writeString(out, name);
                writeNode(out, node.get(name));
            }
        } else if (node.isArray()) {
            out.writeByte('A');
            out.writeInt(node.size());
            for (JsonNode child : node) writeNode(out, child);
        } else if (node.isTextual()) {
            out.writeByte('S');
            writeString(out, node.textValue());
        } else if (node.isBoolean()) {
            out.writeByte(node.booleanValue() ? 'T' : 'F');
        } else if (node.isNull()) {
            out.writeByte('N');
        } else {
            // F008 rejects all other leaf types; fail closed if its schema ever changes.
            throw new IllegalArgumentException("Unsupported reviewed draft value");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
}
