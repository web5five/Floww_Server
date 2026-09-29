package com.floww.server.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.aidraft.AiDraftHttpController;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.infrastructure.JwtProvider;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("!dev")
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtProvider jwt;
    private final ObjectMapper mapper;
    private final boolean walletEnabled;
    private final boolean walletExampleEnabled;

    public JwtAuthFilter(
            JwtProvider jwt,
            ObjectMapper mapper,
            @Value("${floww.auth.wallet.enabled:false}") boolean walletEnabled,
            @Value("${floww.auth.wallet.example-enabled:false}") boolean walletExampleEnabled) {
        this.jwt = jwt;
        this.mapper = mapper;
        this.walletEnabled = walletEnabled;
        this.walletExampleEnabled = walletExampleEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        return (method.equals("GET") && path.equals("/"))
                || path.equals("/actuator/health")
                || path.startsWith("/api/v1/auth/email/")
                || path.equals("/api/v1/admin/auth/signin")
                || (walletEnabled
                && method.equals("POST")
                && (path.equals("/api/v1/auth/wallet/nonce")
                || path.equals("/api/v1/auth/wallet/verify")))
                || (walletEnabled && walletExampleEnabled
                && method.equals("GET")
                && (path.equals("/wallet-signin-example/")
                || path.equals("/wallet-signin-example/app.js")
                || path.equals("/wallet-signin-example/style.css")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String path = request.getServletPath();
        if (path.equals("/api/ai/drafts")) {
            response.setHeader("Cache-Control", "no-store");
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeError(request, response, ErrorCode.UNAUTHORIZED);
            return;
        }

        String token = header.substring(7);
        Optional<JwtProvider.VerifiedToken> verified;
        try {
            verified = jwt.verify(token);
        } catch (IllegalStateException missingSigningKey) {
            writeError(request, response, ErrorCode.INTERNAL_ERROR);
            return;
        }

        if (verified.isEmpty()) {
            writeError(request, response, ErrorCode.UNAUTHORIZED);
            return;
        }

        JwtProvider.VerifiedToken claims = verified.get();

        boolean adminPath = path.startsWith("/api/v1/admin/");
        boolean ownProfilePath = path.equals("/api/v1/users/me");

        if (adminPath
                && (claims.role() != UserRole.ADMIN
                || claims.audience() != TokenAudience.ADMIN)) {
            writeError(request, response, ErrorCode.FORBIDDEN);
            return;
        }

        boolean adminProfileToken = ownProfilePath
                && claims.role() == UserRole.ADMIN
                && claims.audience() == TokenAudience.ADMIN;

        if (!adminPath && !adminProfileToken
                && claims.audience() != TokenAudience.CLIENT) {
            writeError(request, response, ErrorCode.FORBIDDEN);
            return;
        }

        // ExecutionController는 기존 String owner 속성을 사용한다.
        request.setAttribute("owner", claims.userId().toString());
        request.setAttribute("role", claims.role().name());
        request.setAttribute("aud", claims.audience().claim());

        chain.doFilter(request, response);
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode) throws IOException {

        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Object body = request.getServletPath().equals("/api/ai/drafts")
                && errorCode == ErrorCode.UNAUTHORIZED
                ? AiDraftHttpController.unauthorizedBody()
                : ErrorResponse.of(errorCode);

        mapper.writeValue(response.getOutputStream(), body);
    }
}
