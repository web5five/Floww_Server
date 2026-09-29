package com.floww.server.aidraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.f010test.F010HttpTestApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = F010HttpTestApplication.class,
        properties = {"floww.auth.alice-token=alice-token-long-enough",
                "floww.auth.bob-token=bob-token-long-enough", "floww.kiln.api-key=",
                "floww.kiln.base-url=http://127.0.0.1:1/v1"})
class AiDraftHttpNoProviderTest {
    @LocalServerPort int port;

    @Test void missingProviderConfigurationReturns503WithoutNetworkCall() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/drafts"))
                .header("Authorization", "Bearer alice-token-long-enough")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"conversation\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals(503, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("PROVIDER_NOT_CONFIGURED", body.path("error").path("code").asText());
        assertTrue(body.path("draft").isNull());
        assertEquals(0, body.path("evidence").path("attempts").asInt());
        assertEquals("unknown", body.path("evidence").path("usageStatus").asText());
    }
}
