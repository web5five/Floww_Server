package com.floww.server.aiproposal;

import java.math.BigInteger;

/** Exact, bounded uint256 parsing at the AI proposal boundary. No floating point or rounding. */
public final class ExactBaseUnits {
    static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private static final int MAX_BASE_DIGITS = 78;

    private ExactBaseUnits() { }

    public static BigInteger positive(String value) { return parse(value, false); }
    public static BigInteger nonNegativeFee(String value) { return parse(value, true); }

    private static BigInteger parse(String value, boolean allowZero) {
        if (value == null || value.isEmpty() || value.length() > MAX_BASE_DIGITS) return null;
        if (value.charAt(0) == '0') return allowZero && value.length() == 1 ? BigInteger.ZERO : null;
        if (value.charAt(0) < '1' || value.charAt(0) > '9') return null;
        for (int i = 1; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') return null;
        }
        BigInteger parsed = new BigInteger(value);
        return parsed.compareTo(UINT256_MAX) <= 0 ? parsed : null;
    }

    /** Explicit display-to-base-unit adaptation; callers must supply trusted token decimals. */
    public static String displayToPositiveBaseUnits(String display, int decimals) {
        if (decimals < 0 || decimals > 255 || display == null || display.isEmpty()
                || display.length() > MAX_BASE_DIGITS + 1 + 255) return null;
        int dot = display.indexOf('.');
        if (dot == 0 || dot == display.length() - 1 || (dot >= 0 && display.indexOf('.', dot + 1) >= 0)) return null;
        String whole = dot < 0 ? display : display.substring(0, dot);
        String fraction = dot < 0 ? "" : display.substring(dot + 1);
        if (whole.length() > MAX_BASE_DIGITS || fraction.length() > decimals
                || (whole.length() > 1 && whole.charAt(0) == '0')) return null;
        for (int i = 0; i < whole.length(); i++) if (!asciiDigit(whole.charAt(i))) return null;
        for (int i = 0; i < fraction.length(); i++) if (!asciiDigit(fraction.charAt(i))) return null;
        BigInteger units = new BigInteger(whole).multiply(BigInteger.TEN.pow(decimals));
        if (!fraction.isEmpty()) units = units.add(new BigInteger(fraction)
                .multiply(BigInteger.TEN.pow(decimals - fraction.length())));
        return units.signum() > 0 && units.compareTo(UINT256_MAX) <= 0 ? units.toString() : null;
    }

    private static boolean asciiDigit(char value) { return value >= '0' && value <= '9'; }
}
