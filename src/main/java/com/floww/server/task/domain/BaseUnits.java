package com.floww.server.task.domain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 토큰 최소 단위 금액 — 정책과 결정 2장, Issue #16 5번.
 *
 * <p>API·저장·서명 payload의 금액은 10진 정수 문자열이다 (25 fUSDC = "25000000", decimals=6).
 * 부호·소수점·공백·지수 표기·앞자리 0은 거부한다. JSON 숫자는 받지 않는다(호출부에서 문자열만 넘긴다).
 * 변환 중 반올림하지 않는다.
 */
public final class BaseUnits {
    private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,77}");
    /** 한 Task 한도의 상한: 1,000,000 토큰. 그보다 크면 입력 실수로 보고 거절한다. */
    private static final BigInteger MAX_WHOLE_TOKENS = BigInteger.valueOf(1_000_000L);

    private BaseUnits() { }

    /** 양의 정수 문자열만 통과. decimals 기준 허용 범위를 넘으면 빈 값. */
    public static Optional<BigInteger> parsePositive(String raw, int decimals) {
        if (raw == null || !POSITIVE.matcher(raw).matches()) return Optional.empty();
        BigInteger value = new BigInteger(raw);
        if (value.compareTo(max(decimals)) > 0) return Optional.empty();
        return Optional.of(value);
    }

    public static BigInteger max(int decimals) {
        return MAX_WHOLE_TOKENS.multiply(BigInteger.TEN.pow(decimals));
    }

    /** API 응답용 10진 정수 문자열. */
    public static String format(BigInteger value) {
        return value == null ? null : value.toString();
    }
}
