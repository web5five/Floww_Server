package com.floww.server.common.auth;

import com.floww.server.aidraft.AiDraftHttpController;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.common.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DevAuthFilter extends OncePerRequestFilter {
    private final String aliceToken;
    private final String bobToken;
    private final ObjectMapper mapper;
    private final boolean walletEnabled;
    private final boolean walletExampleEnabled;

    public DevAuthFilter(@Value("${floww.auth.alice-token:}") String aliceToken,
                         @Value("${floww.auth.bob-token:}") String bobToken,
                         ObjectMapper mapper,
                         @Value("${floww.auth.wallet.enabled:false}") boolean walletEnabled,
                         @Value("${floww.auth.wallet.example-enabled:false}") boolean walletExampleEnabled) {
        if ((aliceToken.isBlank() && bobToken.isBlank())
                || (!aliceToken.isBlank() && aliceToken.length() < 16)
                || (!bobToken.isBlank() && bobToken.length() < 16)
                || (!aliceToken.isBlank() && aliceToken.equals(bobToken))) {
            throw new IllegalStateException("Set distinct local development bearer tokens of at least 16 characters");
        }
        this.aliceToken = aliceToken;
        this.bobToken = bobToken;
        this.mapper = mapper;
        this.walletEnabled = walletEnabled;
        this.walletExampleEnabled = walletExampleEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health")
                || (walletEnabled && request.getMethod().equals("POST")
                    && (path.equals("/api/v1/auth/wallet/nonce") || path.equals("/api/v1/auth/wallet/verify")))
                || (walletEnabled && walletExampleEnabled && request.getMethod().equals("GET")
                    && (path.equals("/wallet-signin-example/") || path.equals("/wallet-signin-example/app.js")
                        || path.equals("/wallet-signin-example/style.css")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().equals("/api/ai/drafts")) response.setHeader("Cache-Control", "no-store");
        String header = request.getHeader("Authorization");
        String supplied = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        String owner = equal(supplied, aliceToken) ? "alice" : equal(supplied, bobToken) ? "bob" : null;
        if (owner == null) {
            // 필터는 @RestControllerAdvice를 거치지 않으므로 공통 ErrorResponse를 직접 쓴다 (Issue #13, SA 7장).
            // /api/ai/drafts는 F010 계약의 자체 envelope를 유지한다.
            // 한국어 메시지가 있으므로 charset을 명시한다.
            response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Object body = request.getRequestURI().equals("/api/ai/drafts")
                    ? AiDraftHttpController.unauthorizedBody() : ErrorResponse.of(ErrorCode.UNAUTHORIZED);
            mapper.writeValue(response.getOutputStream(), body);
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
