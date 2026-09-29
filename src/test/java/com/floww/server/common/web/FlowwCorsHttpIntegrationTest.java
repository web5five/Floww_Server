package com.floww.server.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("vercel")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "floww.cors.allowed-origins=https://floww.example")
class FlowwCorsHttpIntegrationTest {
    @Autowired TestRestTemplate http;

    @Test
    void allowedPreflightRunsBeforeJwtAndOnlyAllowsExplicitHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://floww.example");
        headers.add("Access-Control-Request-Method", "POST");
        headers.add("Access-Control-Request-Headers", "authorization,content-type,idempotency-key");

        ResponseEntity<String> response = http.exchange("/api/v1/tasks", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("https://floww.example", response.getHeaders().getFirst("Access-Control-Allow-Origin"));
        assertFalse(response.getHeaders().containsKey("Access-Control-Allow-Credentials"));
        String allowed = response.getHeaders().getFirst("Access-Control-Allow-Headers");
        assertEquals("authorization, content-type, idempotency-key", allowed.toLowerCase());
    }

    @Test
    void unknownOriginIsRejectedAndAllowedOriginStillNeedsJwt() {
        HttpHeaders unknown = new HttpHeaders();
        unknown.setOrigin("https://other.example");
        unknown.add("Access-Control-Request-Method", "POST");
        ResponseEntity<String> blocked = http.exchange("/api/v1/tasks", HttpMethod.OPTIONS,
                new HttpEntity<>(unknown), String.class);
        assertEquals(403, blocked.getStatusCode().value());
        assertNull(blocked.getHeaders().getFirst("Access-Control-Allow-Origin"));

        HttpHeaders allowed = new HttpHeaders();
        allowed.setOrigin("https://floww.example");
        ResponseEntity<String> unauthorized = http.exchange("/api/v1/tasks", HttpMethod.GET,
                new HttpEntity<>(allowed), String.class);
        assertEquals(401, unauthorized.getStatusCode().value());
        assertEquals("https://floww.example", unauthorized.getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void invalidOrWildcardOriginsFailClosed() {
        for (String origin : new String[] {"*", "https://floww.example/path", "https://user@floww.example",
                "http://floww.example", "https://floww.example,"}) {
            assertThrows(IllegalArgumentException.class, () -> FlowwCorsConfiguration.parseOrigins(origin));
        }
        assertEquals(0, FlowwCorsConfiguration.parseOrigins("").size());
        assertEquals(2, FlowwCorsConfiguration.parseOrigins(
                "https://floww.example, http://localhost:3000").size());
    }
}
