package com.floww.server.taskaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Bounded JSON-RPC transport. An uncertain send is never interpreted as a failed payment. */
@Component
public class TaskAccountRpc {
    private final TaskAccountConfig config;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong ids = new AtomicLong();
    public TaskAccountRpc(TaskAccountConfig config, ObjectMapper json) { this.config=config; this.json=json; }
    public JsonNode call(String method, Object... params) {
        if (!config.enabled) throw new IllegalStateException("TaskAccount disabled");
        try {
            String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(),
                    "method", method, "params", List.of(params)));
            HttpRequest request = HttpRequest.newBuilder(config.rpcUrl).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 2_000_000) throw new IllegalStateException("RPC unavailable");
            JsonNode root = json.readTree(response.body());
            if (root.hasNonNull("error") || !root.has("result")) throw new IllegalStateException("RPC rejected " + method);
            return root.path("result");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("RPC interrupted"); }
        catch (Exception e) { throw new IllegalStateException("RPC unavailable", e); }
    }
    public String read(String account, String signature) {
        JsonNode result = call("eth_call", Map.of("to",account,"data",TaskAccountArtifact.selector(signature)), "latest");
        String value = result.asText();
        if (!value.matches("0x[0-9a-fA-F]{64}")) throw new IllegalStateException("Invalid RPC getter");
        return value.toLowerCase();
    }
    public String code(String account) { return call("eth_getCode", account, "latest").asText(); }
    public JsonNode receipt(String hash) { return call("eth_getTransactionReceipt", hash); }
    public JsonNode transaction(String hash) { return call("eth_getTransactionByHash", hash); }
    public java.math.BigInteger number(String method, Object... params) {
        String hex = call(method, params).asText();
        if (!hex.matches("0x[0-9a-fA-F]+")) throw new IllegalStateException("Invalid RPC integer");
        return org.web3j.utils.Numeric.toBigInt(hex);
    }
    public void send(String raw, String expectedHash) {
        String returned = call("eth_sendRawTransaction", raw).asText();
        if (!returned.equalsIgnoreCase(expectedHash)) throw new IllegalStateException("RPC transaction hash mismatch");
    }
}
