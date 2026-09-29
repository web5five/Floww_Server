package com.floww.server.auth.infrastructure;

import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    /** HS256 최소 키 길이 (RFC 7518 3.2). */
    static final int MIN_KEY_BYTES = 32;
    /** 비정상적으로 긴 Authorization 헤더를 파싱 전에 거절한다. */
    static final int MAX_TOKEN_LENGTH = 4096;
    static final String ROLE_CLAIM = "role";
    private static final String ALGORITHM = "HS256";
    private static final Logger log = LoggerFactory.getLogger(JwtProvider.class);

    /** 발급된 토큰. toString()에 토큰 값을 넣지 않는다. */
    public record IssuedToken(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "IssuedToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    /** 검증을 통과한 토큰의 내용. 필터가 요청 attribute(owner, role, aud)로 옮긴다. */
    public record VerifiedToken(UUID userId, UserRole role, TokenAudience audience, Instant expiresAt) { }

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public JwtProvider(@Value("${floww.auth.jwt.signing-key:}") String signingKey) {
        this(signingKey, Clock.systemUTC());
    }

    JwtProvider(String signingKey, Clock clock) {
        this.clock = clock;
        if (signingKey == null || signingKey.isBlank()) {
            log.warn("JWT_SIGNING_KEY is not set; access token issue and verification are disabled");
            this.key = null;
            return;
        }
        byte[] bytes = signingKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SIGNING_KEY must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * @throws IllegalArgumentException aud=admin인데 role이 ADMIN이 아닐 때 (서비스 버그 방지)
     * @throws IllegalStateException 서명키가 설정되지 않았을 때
     */
    @SuppressWarnings("deprecation") // audience().single(): AUTH-00 예시대로 aud를 배열이 아닌 문자열로 쓴다.
    public IssuedToken issue(User user, TokenAudience audience) {
        SecretKey signingKey = requireKey();
        if (audience == TokenAudience.ADMIN && user.role() != UserRole.ADMIN) {
            throw new IllegalArgumentException("admin audience requires ADMIN role");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);
        String value = Jwts.builder()
                .subject(user.id().toString())
                .claim(ROLE_CLAIM, user.role().name())
                .audience().single(audience.claim())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(value, ACCESS_TOKEN_TTL.toSeconds());
    }

    /**
     * 서명·만료·필수 클레임을 확인한다. 하나라도 틀리면 빈 값.
     *
     * @throws IllegalStateException 서명키가 설정되지 않았을 때
     */
    public Optional<VerifiedToken> verify(String token) {
        SecretKey signingKey = requireKey();
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) return Optional.empty();
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
            if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) return Optional.empty();

            Claims claims = jws.getPayload();
            Set<String> audiences = claims.getAudience();
            String subject = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (audiences == null || audiences.size() != 1 || subject == null || role == null
                    || claims.getIssuedAt() == null || claims.getExpiration() == null) {
                return Optional.empty();
            }
            Optional<TokenAudience> audience = TokenAudience.fromClaim(audiences.iterator().next());
            if (audience.isEmpty()) return Optional.empty();

            UUID userId = UUID.fromString(subject);
            UserRole userRole = UserRole.valueOf(role);
            if (audience.get() == TokenAudience.ADMIN && userRole != UserRole.ADMIN) return Optional.empty();

            return Optional.of(new VerifiedToken(userId, userRole, audience.get(),
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            // 만료·서명 불일치·형식 오류·알 수 없는 role/UUID. 토큰 값은 로그에 남기지 않는다.
            return Optional.empty();
        }
    }

    private SecretKey requireKey() {
        if (key == null) throw new IllegalStateException("JWT_SIGNING_KEY is not configured");
        return key;
    }
}