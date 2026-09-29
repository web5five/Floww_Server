package com.floww.server.auth.wallet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "floww.auth.wallet", name = "enabled", havingValue = "true")
final class WalletSigninConfig {
    final String origin;
    final String domain;
    final Set<Long> chainIds;

    WalletSigninConfig(@Value("${floww.auth.wallet.origin:}") String origin,
                       @Value("${floww.auth.wallet.chain-ids:}") String chainIds,
                       @Value("${floww.auth.jwt.signing-key:}") String jwtSigningKey) {
        try {
            URI uri = URI.create(origin);
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    || !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority())) {
                throw new IllegalArgumentException();
            }
            this.origin = origin;
            this.domain = uri.getRawAuthority();
            Set<Long> parsed = Arrays.stream(chainIds.split(",", -1)).map(String::trim)
                    .map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
            if (parsed.isEmpty() || parsed.size() > 16 || parsed.stream().anyMatch(id -> id <= 0)) {
                throw new IllegalArgumentException();
            }
            if (jwtSigningKey == null || jwtSigningKey.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException();
            }
            this.chainIds = parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Wallet sign-in requires a trusted origin, allowed chain IDs, and JWT signing key");
        }
    }
}
