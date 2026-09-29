package com.floww.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KilnClient {
    public static final String MODEL = "qwen3-32b";
    public static final int MAX_TOKENS_PER_CALL = 768;
    public record Result(String modelId, String finishReason, String toolCallId, String toolName,
                         String rawArguments, JsonNode arguments, Map<String, Long> usage,
                         String usageStatus, String cost, String generationId, int attempts) { }
    public static class Failure extends RuntimeException {
        private final String code;
        private final int attempts;
        private final Result partial;
        Failure(String code, int attempts) { this(code, attempts, null); }
        Failure(String code, int attempts, Result partial) {
            super(code); this.code = code; this.attempts = attempts; this.partial = partial;
        }
        public String code() { return code; }
        public int attempts() { return attempts; }
        public Result partial() { return partial; }
    }

    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String modelEvidenceMode;

    public KilnClient(ObjectMapper json,
                      @Value("${floww.kiln.base-url}") String baseUrl,
                      @Value("${floww.kiln.api-key:}") String key) {
        this.json = json;
        this.key = key;
        URI base = URI.create(baseUrl);
        boolean local = "http".equals(base.getScheme())
                && ("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()));
        if ((!"https".equals(base.getScheme()) && !local) || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Kiln base URL must be HTTPS or local loopback HTTP");
        }
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.modelEvidenceMode = local ? "local_model_fixture"
                : ("https".equals(base.getScheme()) && "api.bricksum.com".equals(base.getHost())
                && base.getPort() == -1 && "/v1".equals(base.getPath())
                ? "kiln" : "unknown_model_provider");
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean available() { return !key.isBlank(); }
    public String modelEvidenceMode() { return modelEvidenceMode; }

    public Result next(List<Map<String, Object>> messages, List<String> allowedTools, Instant deadline) {
        if (key.isBlank()) throw new Failure("KILN_NOT_CONFIGURED", 0);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : allowedTools) tools.add(tool(name));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", MODEL);
        request.put("stream", false);
        request.put("max_tokens", MAX_TOKENS_PER_CALL);
        request.put("messages", messages);
        if (!tools.isEmpty()) { request.put("tool_choice", "auto"); request.put("tools", tools); }
        String body;
        try { body = json.writeValueAsString(request); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        for (int attempt = 1; attempt <= 2; attempt++) {
            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) throw new Failure("RUN_TIME_LIMIT", attempt - 1);
            try {
                HttpRequest call = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofMillis(Math.min(30000, remaining)))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status != 200) {
                    if (attempt < 2 && (status == 429 || status == 502 || status == 503 || status == 504)) {
                        long delay = status == 429
                                ? retryAfterMillis(response.headers().firstValue("Retry-After").orElse("")) : 250;
                        if (delay >= 0) {
                            if (delay >= Duration.between(Instant.now(), deadline).toMillis())
                                throw new Failure("RUN_TIME_LIMIT", attempt);
                            pause(delay, attempt);
                            continue;
                        }
                    }
                    String code = status == 403 && response.body().contains("Error 1010")
                            ? "PROVIDER_ACCESS_BLOCK_1010" : "PROVIDER_HTTP_" + status;
                    throw new Failure(code, attempt);
                }
                if (response.body().length() > 131072) throw new Failure("MODEL_OUTPUT_INVALID", attempt);
                return parseResponse(response, attempt);
            } catch (IOException e) {
                if (!Instant.now().isBefore(deadline)) throw new Failure("RUN_TIME_LIMIT", attempt);
                if (attempt == 2) throw new Failure("PROVIDER_UNAVAILABLE", attempt);
                if (Duration.between(Instant.now(), deadline).toMillis() <= 250)
                    throw new Failure("RUN_TIME_LIMIT", attempt);
                pause(250, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failure("PROVIDER_INTERRUPTED", attempt);
            } catch (IllegalArgumentException e) {
                throw new Failure("PROVIDER_REQUEST_INVALID", attempt);
            }
        }
        throw new Failure("PROVIDER_UNAVAILABLE", 2);
    }

    private static Map<String, Object> tool(String name) {
        String argument = switch (name) {
            case "search_offers" -> "itemId";
            case "get_quote" -> "offerId";
            case "propose_purchase" -> "quoteId";
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
        String description = switch (name) {
            case "search_offers" -> "Find server-merchant offers for the mandate item ID.";
            case "get_quote" -> "Get a full-cost merchant quote for an offer ID returned by search_offers.";
            default -> "Propose a quote ID returned by get_quote for local policy review only; no payment.";
        };
        return Map.of("type", "function", "function", Map.of("name", name,
                "description", description, "parameters", Map.of("type", "object",
                        "additionalProperties", false, "required", List.of(argument),
                        "properties", Map.of(argument, Map.of("type", "string", "description", argument)))));
    }

    private static long retryAfterMillis(String value) {
        if (value.isBlank()) return 250;
        try { long seconds = Long.parseLong(value); return seconds >= 0 && seconds <= 2 ? seconds * 1000 : -1; }
        catch (NumberFormatException e) { return -1; }
    }
    private static void pause(long millis, int attempt) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt();
            throw new Failure("PROVIDER_INTERRUPTED", attempt); }
    }

    private Result parseResponse(HttpResponse<String> response, int attempts) {
        JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (JsonProcessingException e) { throw new Failure("MODEL_OUTPUT_INVALID", attempts); }
        if (root == null || !root.isObject() || !root.path("model").isTextual())
            throw new Failure("MODEL_OUTPUT_INVALID", attempts);
        JsonNode usageNode = root.path("usage");
        Map<String, Long> usage = new LinkedHashMap<>();
        boolean validUsage = usageNode.isObject();
        for (String field : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            JsonNode value = usageNode.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) validUsage = false;
            else usage.put(field, value.longValue());
        }
        if (!validUsage) usage = Map.of();
        String cost = usageNode.path("cost").isTextual()
                && usageNode.path("cost").textValue().matches("[0-9]+(?:\\.[0-9]+)?")
                ? usageNode.path("cost").textValue() : null;
        String generation = response.headers().firstValue("X-Neocloud-Generation-Id")
                .filter(v -> v.matches("[A-Za-z0-9._:-]{1,128}")).orElse(null);
        Result partial = new Result(root.path("model").textValue(), "invalid", null, null,
                null, null, usage, validUsage ? "reported" : "unknown", cost, generation, attempts);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1)
            throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
        JsonNode choice = choices.get(0);
        String finish = choice.path("finish_reason").asText("");
        if (finish.equals("length")) throw new Failure("MODEL_OUTPUT_TRUNCATED", attempts, partial);
        if (!finish.equals("tool_calls") && !finish.equals("stop"))
            throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
        JsonNode calls = choice.path("message").path("tool_calls");
        if (finish.equals("stop")) {
            if (!calls.isMissingNode() && !calls.isNull() && calls.size() != 0)
                throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
            return new Result(root.path("model").textValue(), finish, null, null, null, null,
                    usage, validUsage ? "reported" : "unknown", cost, generation, attempts);
        }
        if (!calls.isArray() || calls.size() != 1)
            throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
        JsonNode call = calls.get(0);
        if (!"function".equals(call.path("type").asText()) || !call.path("id").isTextual()
                || !call.path("id").textValue().matches("[A-Za-z0-9._:-]{1,128}")
                || !call.path("function").path("name").isTextual()
                || !call.path("function").path("arguments").isTextual())
            throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
        String raw = call.path("function").path("arguments").textValue();
        if (raw.length() > 2048) throw new Failure("MODEL_OUTPUT_INVALID", attempts, partial);
        JsonNode args;
        try { args = json.readTree(raw); }
        catch (JsonProcessingException e) { args = null; }
        return new Result(root.path("model").textValue(), finish, call.path("id").textValue(),
                call.path("function").path("name").textValue(), raw, args, usage,
                validUsage ? "reported" : "unknown", cost, generation, attempts);
    }
}
