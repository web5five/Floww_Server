package com.floww.server.merchant;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 견적·위임·승인이 공통으로 쓰는 결제 자산 — Issue #34 (#16 금액 표현).
 *
 * <p>정책과 결정 2장: 금액은 토큰 최소 단위 10진 정수 문자열이고, chainId·tokenAddress·decimals와 함께 해석한다.
 * 데모 기본값은 Sepolia fUSDC(decimals=6)다. 주소는 코드가 아니라 설정에서 바꾼다.
 */
@Component
public class SettlementAsset {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    private final long chainId;
    private final String tokenAddress;
    private final int decimals;
    private final String symbol;

    public SettlementAsset(
            @Value("${floww.task.chain-id:11155111}") long chainId,
            @Value("${floww.task.token-address:0x84B494ff145a545D286321691a9B4Febe6947D6A}") String tokenAddress,
            @Value("${floww.task.token-decimals:6}") int decimals,
            @Value("${floww.task.token-symbol:fUSDC}") String symbol) {
        String normalized = tokenAddress == null ? "" : tokenAddress.trim().toLowerCase(Locale.ROOT);
        if (chainId <= 0 || !ADDRESS.matcher(normalized).matches() || decimals < 0 || decimals > 36
                || symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("Invalid settlement asset configuration");
        }
        this.chainId = chainId;
        this.tokenAddress = normalized;
        this.decimals = decimals;
        this.symbol = symbol;
    }

    public long chainId() { return chainId; }

    /** 소문자 0x 주소. */
    public String tokenAddress() { return tokenAddress; }

    public int decimals() { return decimals; }

    public String symbol() { return symbol; }
}
