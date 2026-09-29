package com.floww.server.taskaccount;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.web3j.crypto.Hash;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/** ReviewSnapshotV1 = keccak256(abi.encode(ten exact fields)); outer EIP-712 = contract MandateApproval. */
public final class TaskAccountCrypto {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TaskAccountCrypto() { }
    public record Review(UUID taskId, UUID mandateId, int mandateVersion, UUID attemptId, String merchantId,
                         String quoteId, BigInteger amount, String recipient, String token, Instant quoteExpiry) { }
    public record Approval(String owner, String account, String taskId, String reviewDigest, String token,
                           String recipient, String executor, String reporter, BigInteger maxSpend,
                           long expiresAt, BigInteger nonce, long chainId) { }
    public static String taskId(UUID id) {
        return Numeric.toHexString(Hash.sha3(("floww:task:" + id.toString().toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8)));
    }
    private static String uuid16(UUID id) {return id.toString().replace("-", "").toLowerCase(Locale.ROOT);}
    private static String bytes16(UUID id) {return uuid16(id)+"0".repeat(32);}
    private static String dynamic(String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        String hex=Numeric.toHexStringNoPrefix(bytes);
        return TaskAccountArtifact.word(BigInteger.valueOf(bytes.length))+hex+"0".repeat((64-hex.length()%64)%64);
    }
    /** ABI tuple: bytes32, bytes16, uint256, bytes16, string, string, uint256, address, address, uint64. */
    public static String reviewEncoded(Review r) {
        if(r.mandateVersion()<=0 || r.amount().signum()<=0 || r.quoteExpiry().getEpochSecond()<=0)
            throw new IllegalArgumentException("Invalid reviewed snapshot");
        String merchant=dynamic(r.merchantId()), quote=dynamic(r.quoteId());
        String head=TaskAccountArtifact.word(taskId(r.taskId()))+bytes16(r.mandateId())
                +TaskAccountArtifact.word(BigInteger.valueOf(r.mandateVersion()))+bytes16(r.attemptId())
                +TaskAccountArtifact.word(BigInteger.valueOf(320))
                +TaskAccountArtifact.word(BigInteger.valueOf(320+merchant.length()/2))
                +TaskAccountArtifact.word(r.amount())+TaskAccountArtifact.word(r.recipient())
                +TaskAccountArtifact.word(r.token())
                +TaskAccountArtifact.word(BigInteger.valueOf(r.quoteExpiry().getEpochSecond()));
        return "0x"+head+merchant+quote;
    }
    public static String reviewDigest(Review r) {return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(reviewEncoded(r))));}
    public static Map<String,Object> reviewSchema(Review r) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("schema","ReviewSnapshotV1");
        m.put("types",List.of("bytes32","bytes16","uint256","bytes16","string","string","uint256","address","address","uint64"));
        m.put("fieldOrder",List.of("taskId","mandateId","mandateVersion","attemptId","merchantId","quoteId",
                "amountBaseUnits","recipient","token","quoteExpiresAt"));
        m.put("values",List.of(taskId(r.taskId()),"0x"+uuid16(r.mandateId()),Integer.toString(r.mandateVersion()),
                "0x"+uuid16(r.attemptId()),r.merchantId(),r.quoteId(),r.amount().toString(),r.recipient(),r.token(),
                Long.toString(r.quoteExpiry().getEpochSecond())));
        return m;
    }
    public static Map<String, Object> approvalTypedData(Approval a) {
        return typed("FlowwTaskAccount", a.chainId(), a.account(), "MandateApproval", List.of(
                field("owner", "address"), field("taskId", "bytes32"), field("reviewSnapshotDigest", "bytes32"),
                field("token", "address"), field("recipient", "address"), field("executor", "address"),
                field("fulfillmentReporter", "address"), field("maxSpend", "uint256"),
                field("expiresAt", "uint64"), field("nonce", "uint256")),
                Map.of("owner", a.owner(), "taskId", a.taskId(), "reviewSnapshotDigest", a.reviewDigest(),
                        "token", a.token(), "recipient", a.recipient(), "executor", a.executor(),
                        "fulfillmentReporter", a.reporter(), "maxSpend", a.maxSpend().toString(),
                        "expiresAt", Long.toString(a.expiresAt()), "nonce", a.nonce().toString()));
    }
    public static String digest(Map<String, Object> typed) {
        try { return Numeric.toHexString(new StructuredDataEncoder(JSON.writeValueAsString(typed)).hashStructuredData()); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static String paymentId(UUID attemptId) {
        return Numeric.toHexString(Hash.sha3(("floww:payment:" + attemptId).getBytes(StandardCharsets.UTF_8)));
    }
    public static String evidenceHash(String orderId, String fulfillmentId) {
        return Numeric.toHexString(Hash.sha3(("floww:simulated-fulfillment:" + orderId + ":" + fulfillmentId)
                .getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, String> field(String name, String type) { return Map.of("name", name, "type", type); }
    private static Map<String, Object> typed(String name, long chain, String contract, String primary,
                                             List<Map<String, String>> fields, Map<String, String> message) {
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", name); domain.put("version", "1"); domain.put("chainId", chain);
        List<Map<String, String>> domainFields = new java.util.ArrayList<>(List.of(field("name", "string"),
                field("version", "string"), field("chainId", "uint256")));
        if (contract != null) { domain.put("verifyingContract", contract); domainFields.add(field("verifyingContract", "address")); }
        Map<String, Object> types = new LinkedHashMap<>(); types.put("EIP712Domain", domainFields); types.put(primary, fields);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("types", types); result.put("primaryType", primary);
        result.put("domain", domain); result.put("message", message); return result;
    }
}
