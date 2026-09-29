package com.floww.server;

import com.floww.server.aidraft.AiDraftHttpController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DevAuthFilter extends OncePerRequestFilter {
    private final String aliceToken;
    private final String bobToken;
    private final ObjectMapper mapper;

    public DevAuthFilter(@Value("${floww.auth.alice-token:}") String aliceToken,
                         @Value("${floww.auth.bob-token:}") String bobToken,
                         ObjectMapper mapper) {
        if ((aliceToken.isBlank() && bobToken.isBlank())
                || (!aliceToken.isBlank() && aliceToken.length() < 16)
                || (!bobToken.isBlank() && bobToken.length() < 16)
                || (!aliceToken.isBlank() && aliceToken.equals(bobToken))) {
            throw new IllegalStateException("Set distinct local development bearer tokens of at least 16 characters");
        }
        this.aliceToken = aliceToken;
        this.bobToken = bobToken;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().equals("/api/ai/drafts")) response.setHeader("Cache-Control", "no-store");
        String header = request.getHeader("Authorization");
        String supplied = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        String owner = equal(supplied, aliceToken) ? "alice" : equal(supplied, bobToken) ? "bob" : null;
        if (owner == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), request.getRequestURI().equals("/api/ai/drafts")
                    ? AiDraftHttpController.unauthorizedBody() : Map.of("code", "UNAUTHORIZED"));
            return;
        }
        request.setAttribute("owner", owner);
        chain.doFilter(request, response);
    }

    private static boolean equal(String supplied, String expected) {
        return !expected.isBlank() && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
