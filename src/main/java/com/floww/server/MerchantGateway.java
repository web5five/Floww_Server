package com.floww.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-owned merchant port. The only included adapter is a loopback test fixture. */
@Component
public class MerchantGateway {
    public record Offer(String offerId, String itemId) { }
    public record Quote(String quoteId, String offerId, String itemId, BigDecimal totalCost,
                        String currency, String recipient, Instant expiresAt) { }
    public static class Failure extends RuntimeException {
        private final String code;
        Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private final URI base;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public MerchantGateway(ObjectMapper json,
            @Value("${floww.merchant.test-base-url:}") String testBaseUrl) {
        this.json = json;
        if (testBaseUrl.isBlank()) { base = null; return; }
        URI url = URI.create(testBaseUrl);
        if (!"http".equals(url.getScheme()) || !("localhost".equals(url.getHost())
                || "127.0.0.1".equals(url.getHost())) || url.getUserInfo() != null
                || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("Test merchant must be loopback HTTP");
        }
        base = URI.create(testBaseUrl.replaceAll("/+$", ""));
    }

    public boolean available() { return base != null; }
    public String evidenceMode() { return "local_test_merchant"; }

    public List<Offer> searchOffers(String itemId) {
        JsonNode root = get("/offers?itemId=" + enc(itemId));
        if (!root.isObject() || root.size() != 1) throw new Failure("MERCHANT_RESPONSE_INVALID");
        JsonNode offers = root.path("offers");
        if (!offers.isArray() || offers.size() > 10) throw new Failure("MERCHANT_RESPONSE_INVALID");
        try {
            List<Offer> found = new ArrayList<>();
            for (JsonNode offer : offers) {
                if (!offer.isObject() || offer.size() != 2) throw new Failure("MERCHANT_RESPONSE_INVALID");
                found.add(new Offer(Inputs.quoteItem(offer.path("offerId")),
                        Inputs.quoteItem(offer.path("itemId"))));
            }
            return found;
        } catch (ApiException e) { throw new Failure("MERCHANT_RESPONSE_INVALID"); }
    }

    public Quote getQuote(String offerId) {
        JsonNode q = get("/quotes?offerId=" + enc(offerId));
        if (!q.isObject() || q.size() != 7) throw new Failure("MERCHANT_RESPONSE_INVALID");
        try {
            return new Quote(Inputs.quoteItem(q.path("quoteId")), Inputs.quoteItem(q.path("offerId")),
                    Inputs.quoteItem(q.path("itemId")), Inputs.cost(q.path("totalCost")),
                    Inputs.quoteCurrency(q.path("currency")), Inputs.quoteRecipient(q.path("recipient")),
                    Inputs.quoteExpiry(q.path("expiresAt")));
        } catch (ApiException e) { throw new Failure("MERCHANT_RESPONSE_INVALID"); }
    }

    private JsonNode get(String suffix) {
        if (base == null) throw new Failure("MERCHANT_NOT_CONFIGURED");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + suffix))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> result = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() != 200) throw new Failure("MERCHANT_HTTP_" + result.statusCode());
            if (result.body().length() > 8192) throw new Failure("MERCHANT_RESPONSE_INVALID");
            JsonNode node = json.readTree(result.body());
            if (node == null) throw new Failure("MERCHANT_RESPONSE_INVALID");
            return node;
        } catch (JsonProcessingException e) { throw new Failure("MERCHANT_RESPONSE_INVALID"); }
          catch (IOException e) { throw new Failure("MERCHANT_UNAVAILABLE"); }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new Failure("MERCHANT_INTERRUPTED");
        } catch (ApiException | IllegalArgumentException e) { throw new Failure("MERCHANT_RESPONSE_INVALID"); }
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
