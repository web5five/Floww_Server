package com.floww.server.auth.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;
import org.web3j.crypto.Keys;

final class WalletSigninInputs {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern SIGNATURE = Pattern.compile("0x[0-9a-fA-F]{130}");

    private WalletSigninInputs() { }

    static String address(JsonNode node) {
        if (node == null || !node.isTextual()) throw new ApiException(ErrorCode.INVALID_INPUT);
        String value = node.textValue();
        if (!ADDRESS.matcher(value).matches()) throw new ApiException(ErrorCode.INVALID_INPUT);
        String checksum = Keys.toChecksumAddress(value);
        String letters = value.substring(2);
        boolean mixed = !letters.equals(letters.toLowerCase(Locale.ROOT))
                && !letters.equals(letters.toUpperCase(Locale.ROOT));
        if (mixed && !checksum.equals(value)) throw new ApiException(ErrorCode.INVALID_INPUT);
        return value.toLowerCase(Locale.ROOT);
    }

    static long chainId(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }
        return node.longValue();
    }

    static String message(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().length() > 1024
                || node.textValue().isBlank()) throw new ApiException(ErrorCode.INVALID_INPUT);
        return node.textValue();
    }

    static String signature(JsonNode node) {
        if (node == null || !node.isTextual() || !SIGNATURE.matcher(node.textValue()).matches()) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }
        return node.textValue();
    }
}
