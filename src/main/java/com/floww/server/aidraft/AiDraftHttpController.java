package com.floww.server.aidraft;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.KilnClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Proposal-only transport. The authenticated owner is never taken from the body. */
@RestController
public class AiDraftHttpController {
    public static final String CONTRACT = "ai-draft-http.v1";
    public static final int MAX_BODY_BYTES = 128 * 1024;
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final AiDraftAdapter adapter;

    public AiDraftHttpController(KilnClient kiln) {
        this.adapter = new AiDraftAdapter(kiln, Clock.systemUTC());
    }

    @PostMapping("/api/ai/drafts")
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request) throws IOException {
        if (!jsonContentType(request.getContentType()))
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", null);
        if (request.getContentLengthLong() > MAX_BODY_BYTES)
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", null);
        // The extra byte is a sentinel; this also bounds chunked requests without Content-Length.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES)
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", null);
        List<AiDraftAdapter.Turn> conversation = parseConversation(body);
        if (conversation == null) return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", null);
        AiDraftAdapter.Outcome outcome = adapter.propose(conversation);
        if (outcome.failureCode() != null) {
            return switch (outcome.failureCode()) {
                case "CONVERSATION_INVALID" -> error(HttpStatus.BAD_REQUEST, "INVALID_CONVERSATION", null);
                case "KILN_NOT_CONFIGURED" -> error(HttpStatus.SERVICE_UNAVAILABLE,
                        "PROVIDER_NOT_CONFIGURED", outcome.provenance());
                case "RUN_TIME_LIMIT" -> error(HttpStatus.GATEWAY_TIMEOUT,
                        "PROVIDER_TIMEOUT", outcome.provenance());
                default -> error(HttpStatus.BAD_GATEWAY, "MODEL_PROPOSAL_FAILED", outcome.provenance());
            };
        }
        AiDraftPreflight.Result result = outcome.preflight();
        if (result == null || "INVALID_PROPOSAL".equals(result.status()))
            return error(HttpStatus.BAD_GATEWAY, "MODEL_PROPOSAL_INVALID", outcome.provenance());
        Map<String, Object> response = envelope(result.status(), result.draft(), result.issues(),
                outcome.provenance(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static boolean jsonContentType(String raw) {
        if (raw == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(raw);
            return "application".equalsIgnoreCase(type.getType())
                    && "json".equalsIgnoreCase(type.getSubtype())
                    && (type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static List<AiDraftAdapter.Turn> parseConversation(byte[] body) {
        String input;
        try {
            input = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
        try (JsonParser parser = STRICT_JSON.getFactory().createParser(input)) {
            JsonNode root = STRICT_JSON.readTree(parser);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("conversation")
                    || parser.nextToken() != null) return null;
            JsonNode turns = root.get("conversation");
            if (!turns.isArray()) return null;
            List<AiDraftAdapter.Turn> conversation = new ArrayList<>();
            for (JsonNode turn : turns) {
                if (!turn.isObject() || turn.size() != 2 || !turn.path("role").isTextual()
                        || !turn.path("content").isTextual()) return null;
                conversation.add(new AiDraftAdapter.Turn(turn.get("role").textValue(),
                        turn.get("content").textValue()));
            }
            return conversation;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                              AiDraftAdapter.Provenance provenance) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(envelope("ERROR", null, List.of(), provenance, code));
    }

    public static Map<String, Object> unauthorizedBody() {
        return envelope("ERROR", null, List.of(), null, "UNAUTHORIZED");
    }

    private static Map<String, Object> envelope(String status, JsonNode draft,
                                                 List<AiDraftPreflight.Issue> issues,
                                                 AiDraftAdapter.Provenance provenance, String code) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("httpContractVersion", CONTRACT);
        response.put("status", status);
        response.put("draft", draft);
        response.put("issues", issues);
        response.put("evidence", evidence(provenance));
        response.put("error", code == null ? null : Map.of("code", code));
        return response;
    }

    private static Map<String, Object> evidence(AiDraftAdapter.Provenance provenance) {
        if (provenance == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", KilnClient.MODEL.equals(provenance.modelId()) ? provenance.modelId() : null);
        result.put("modelEvidenceMode", provenance.modelEvidenceMode());
        result.put("toolCallId", provenance.toolCallId());
        result.put("generationId", provenance.generationId());
        result.put("usageStatus", provenance.usageStatus());
        result.put("usage", provenance.usage());
        result.put("cost", provenance.cost());
        result.put("attempts", provenance.attempts());
        return result;
    }
}
