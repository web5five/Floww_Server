package com.floww.server.auth.wallet;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "floww.auth.wallet", name = "enabled", havingValue = "true")
final class WalletSigninJsonFilter extends OncePerRequestFilter {
    static final String BODY_ATTRIBUTE = WalletSigninJsonFilter.class.getName() + ".body";
    private static final int MAX_BYTES = 8192;
    private final ObjectMapper strict;
    private final ObjectMapper responseMapper;

    WalletSigninJsonFilter(ObjectMapper mapper) {
        responseMapper = mapper;
        strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !request.getMethod().equals("POST") || !(path.equals("/api/v1/auth/wallet/nonce")
                || path.equals("/api/v1/auth/wallet/verify"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String type = request.getContentType();
        try {
            MediaType media = type == null ? null : MediaType.parseMediaType(type);
            if (media == null || !media.getType().equals("application") || !media.getSubtype().equals("json")
                    || (media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset()))) {
                error(response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                return;
            }
        } catch (IllegalArgumentException e) {
            error(response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            error(response, ErrorCode.REQUEST_TOO_LARGE);
            return;
        }
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = strict.readTree(body);
            if (node == null) throw new IOException("empty JSON");
            request.setAttribute(BODY_ATTRIBUTE, node);
        } catch (CharacterCodingException | com.fasterxml.jackson.core.JsonProcessingException e) {
            error(response, ErrorCode.MALFORMED_JSON);
            return;
        } catch (IOException e) {
            error(response, ErrorCode.MALFORMED_JSON);
            return;
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        responseMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
