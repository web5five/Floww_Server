package com.floww.server.taskaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.web3j.utils.Numeric;

/** Pinned d4e6a7d source, solc 0.8.28, optimizer 200. */
@Component
public class TaskAccountArtifact {
    private final String creation;
    private final String runtime;
    private final JsonNode refs;
    public TaskAccountArtifact(ObjectMapper json) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/taskaccount/FlowwTaskAccount.json")) {
            if (in == null) throw new IllegalStateException("Pinned account artifact missing");
            JsonNode root = json.readTree(in);
            if (!"d4e6a7d7b7635634b8a59f7c87bba91d3b311f9d".equals(root.path("sourceCommit").asText())
                    || !"1e505163dd0edb72d8038a665902914788d86a3a5233f2fbbdbddee62344f59b".equals(root.path("sourceSha256").asText())) {
                throw new IllegalStateException("Unpinned account artifact");
            }
            JsonNode evm = root.path("artifact").path("evm");
            creation = "0x" + evm.path("bytecode").path("object").asText();
            runtime = evm.path("deployedBytecode").path("object").asText();
            refs = evm.path("deployedBytecode").path("immutableReferences");
            if (creation.length() < 1000 || runtime.length() < 1000 || !refs.isObject())
                throw new IllegalStateException("Incomplete account artifact");
        }
    }
    public String deployment(String owner, String taskId, String review, String token, String recipient,
                             String executor, String reporter, BigInteger amount, long expiry) {
        return creation + word(owner) + word(taskId) + word(review) + word(token) + word(recipient)
                + word(executor) + word(reporter) + word(amount) + word(BigInteger.valueOf(expiry));
    }
    /** Compare all non-immutable runtime bytes; immutable values are checked separately through getters. */
    public boolean matchesRuntime(String code) {
        if (code == null || !code.matches("0x[0-9a-fA-F]+")) return false;
        byte[] expected = Numeric.hexStringToByteArray("0x" + runtime);
        byte[] actual = Numeric.hexStringToByteArray(code);
        if (actual.length != expected.length) return false;
        var ids = refs.fields();
        while (ids.hasNext()) {
            JsonNode positions = ids.next().getValue();
            for (JsonNode p : positions) {
                int start = p.path("start").asInt(), length = p.path("length").asInt();
                if (start < 0 || length != 32 || start + length > expected.length) return false;
                for (int i = start; i < start + length; i++) actual[i] = expected[i];
            }
        }
        return java.util.Arrays.equals(expected, actual);
    }
    public static String word(String hex) { return word(Numeric.toBigInt(hex)); }
    public static String word(BigInteger n) {
        if (n.signum() < 0 || n.bitLength() > 256) throw new IllegalArgumentException("word out of range");
        return Numeric.toHexStringNoPrefixZeroPadded(n, 64).toLowerCase(Locale.ROOT);
    }
    public static String selector(String signature) {
        return org.web3j.utils.Numeric.toHexString(org.web3j.crypto.Hash.sha3(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 10);
    }
}
