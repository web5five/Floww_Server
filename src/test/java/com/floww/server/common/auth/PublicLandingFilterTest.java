package com.floww.server.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.auth.infrastructure.JwtProvider;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicLandingFilterTest {
    private final JwtAuthFilter filter = new JwtAuthFilter(
            new JwtProvider("public-landing-test-signing-key-32-bytes"), new ObjectMapper(), false, false);

    @Test
    void onlyLandingGetIsPublic() throws Exception {
        MockHttpServletRequest landing = request("GET", "/");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(landing, new MockHttpServletResponse(), chain);
        assertTrue(filter.shouldNotFilter(landing));
        assertEquals(landing, chain.getRequest());

        assertFalse(filter.shouldNotFilter(request("POST", "/")));
        assertFalse(filter.shouldNotFilter(request("GET", "/api/v1/tasks")));

        MockHttpServletResponse protectedResponse = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/tasks"), protectedResponse, new MockFilterChain());
        assertEquals(401, protectedResponse.getStatus());
    }

    @Test
    void landingPageLabelsSimulationAndProvidesEvidenceLinks() throws Exception {
        String html = new ClassPathResource("static/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("not real pharmacies"));
        assertTrue(html.contains("not evidence of real-world delivery"));
        assertTrue(html.contains("/actuator/health"));
        assertTrue(html.contains("TASKACCOUNT_E2E_KO_EN.md"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
