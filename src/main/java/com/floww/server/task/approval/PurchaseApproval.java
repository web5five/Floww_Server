package com.floww.server.task.approval;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * EIP-712 구매 승인 typed data — Issue #35 (#32 3번), 정책과 결정 3장.
 *
 * <p>domain은 {@code name=Floww, version=1, chainId}. verifyingContract는 결정 문서에 없어 넣지 않는다.
 * 서명 대상 필드는 서버가 저장된 mandate·quote·레지스트리에서 채운다. 클라이언트가 보낸 값으로 만들지 않는다.
 *
 * <p>digest는 이 클래스가 직접 계산한다(평평한 struct 하나라 인코딩 규칙이 단순하다).
 * 테스트에서 web3j StructuredDataEncoder 결과와 일치하는지 교차 확인한다.
 */
public final class PurchaseApproval {
    public static final String DOMAIN_NAME = "Floww";
    public static final String DOMAIN_VERSION = "1";
    public static final String PRIMARY_TYPE = "PurchaseApproval";

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");
    private static final Pattern BYTES32 = Pattern.compile("0x[0-9a-f]{64}");
    private static final Pattern SIGNATURE = Pattern.compile("0x[0-9a-fA-F]{130}");
    private static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
    /** secp256k1 n/2. s가 이보다 크면 malleable 서명으로 보고 거절한다 (EIP-2). */
    private static final BigInteger HALF_N = new BigInteger(
            "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16);

    private record Field(String name, String type) { }

    private static final List<Field> DOMAIN_FIELDS = List.of(
            new Field("name", "string"), new Field("version", "string"), new Field("chainId", "uint256"));
    private static final List<Field> MESSAGE_FIELDS = List.of(
            new Field("taskId", "string"),
            new Field("mandateId", "string"),
            new Field("version", "uint256"),
            new Field("merchantId", "string"),
            new Field("quoteId", "string"),
            new Field("recipientAddress", "address"),
            new Field("tokenAddress", "address"),
            new Field("amountBaseUnits", "uint256"),
            new Field("maxAmountBaseUnits", "uint256"),
            new Field("expiresAt", "uint256"),
            new Field("nonce", "bytes32"));

    private static final byte[] DOMAIN_TYPE_HASH = Hash.sha3(typeString("EIP712Domain", DOMAIN_FIELDS)
            .getBytes(StandardCharsets.UTF_8));
    private static final byte[] MESSAGE_TYPE_HASH = Hash.sha3(typeString(PRIMARY_TYPE, MESSAGE_FIELDS)
            .getBytes(StandardCharsets.UTF_8));

    /** 서명 대상. 주소는 소문자 0x, nonce는 소문자 0x + 64 hex, expiresAt은 unix seconds. */
    public record Message(String taskId, String mandateId, int version, String merchantId, String quoteId,
                          String recipientAddress, String tokenAddress, BigInteger amountBaseUnits,
                          BigInteger maxAmountBaseUnits, long expiresAt, String nonce) {
        public Message {
            if (!ADDRESS.matcher(recipientAddress).matches() || !ADDRESS.matcher(tokenAddress).matches()
                    || !BYTES32.matcher(nonce).matches() || version <= 0 || expiresAt <= 0
                    || amountBaseUnits.signum() <= 0 || maxAmountBaseUnits.signum() <= 0
                    || amountBaseUnits.compareTo(UINT256_MAX) > 0 || maxAmountBaseUnits.compareTo(UINT256_MAX) > 0) {
                throw new IllegalArgumentException("invalid purchase approval message");
            }
        }
    }

    private PurchaseApproval() { }

    /**
     * 프론트가 {@code eth_signTypedData_v4}에 그대로 넘기는 JSON 구조.
     * uint256 값은 JS 정밀도 손실을 막기 위해 10진 문자열로 넣는다 (domain.chainId만 숫자).
     */
    public static Map<String, Object> typedData(long chainId, Message m) {
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("EIP712Domain", fieldsJson(DOMAIN_FIELDS));
        types.put(PRIMARY_TYPE, fieldsJson(MESSAGE_FIELDS));
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", DOMAIN_NAME);
        domain.put("version", DOMAIN_VERSION);
        domain.put("chainId", chainId);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("taskId", m.taskId());
        message.put("mandateId", m.mandateId());
        message.put("version", Integer.toString(m.version()));
        message.put("merchantId", m.merchantId());
        message.put("quoteId", m.quoteId());
        message.put("recipientAddress", m.recipientAddress());
        message.put("tokenAddress", m.tokenAddress());
        message.put("amountBaseUnits", m.amountBaseUnits().toString());
        message.put("maxAmountBaseUnits", m.maxAmountBaseUnits().toString());
        message.put("expiresAt", Long.toString(m.expiresAt()));
        message.put("nonce", m.nonce());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("types", types);
        root.put("primaryType", PRIMARY_TYPE);
        root.put("domain", domain);
        root.put("message", message);
        return root;
    }

    /** keccak256(0x1901 ‖ domainSeparator ‖ hashStruct(message)). */
    public static byte[] digest(long chainId, Message m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x19);
        out.write(0x01);
        out.writeBytes(domainSeparator(chainId));
        out.writeBytes(hashStruct(m));
        return Hash.sha3(out.toByteArray());
    }

    public static String digestHex(long chainId, Message m) {
        return Numeric.toHexString(digest(chainId, m));
    }

    static byte[] domainSeparator(long chainId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(DOMAIN_TYPE_HASH);
        out.writeBytes(Hash.sha3(DOMAIN_NAME.getBytes(StandardCharsets.UTF_8)));
        out.writeBytes(Hash.sha3(DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8)));
        out.writeBytes(uint(BigInteger.valueOf(chainId)));
        return Hash.sha3(out.toByteArray());
    }

    static byte[] hashStruct(Message m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MESSAGE_TYPE_HASH);
        out.writeBytes(string(m.taskId()));
        out.writeBytes(string(m.mandateId()));
        out.writeBytes(uint(BigInteger.valueOf(m.version())));
        out.writeBytes(string(m.merchantId()));
        out.writeBytes(string(m.quoteId()));
        out.writeBytes(address(m.recipientAddress()));
        out.writeBytes(address(m.tokenAddress()));
        out.writeBytes(uint(m.amountBaseUnits()));
        out.writeBytes(uint(m.maxAmountBaseUnits()));
        out.writeBytes(uint(BigInteger.valueOf(m.expiresAt())));
        out.writeBytes(Numeric.hexStringToByteArray(m.nonce()));
        return Hash.sha3(out.toByteArray());
    }

    /**
     * 65바이트 서명(r‖s‖v)에서 서명자 주소를 복구한다. v는 27/28 또는 0/1을 허용한다.
     * 형식이 틀리거나 복구할 수 없으면 빈 값. 반환 주소는 소문자 0x.
     */
    public static Optional<String> recoverSigner(byte[] digest, String signatureHex) {
        if (signatureHex == null || !SIGNATURE.matcher(signatureHex).matches()) return Optional.empty();
        byte[] sig = Numeric.hexStringToByteArray(signatureHex);
        byte v = sig[64];
        if (v == 0 || v == 1) v += 27;
        if (v != 27 && v != 28) return Optional.empty();
        byte[] r = Arrays.copyOfRange(sig, 0, 32);
        byte[] s = Arrays.copyOfRange(sig, 32, 64);
        if (new BigInteger(1, s).compareTo(HALF_N) > 0 || new BigInteger(1, r).signum() == 0) return Optional.empty();
        try {
            BigInteger key = Sign.signedMessageHashToKey(digest, new Sign.SignatureData(v, r, s));
            return Optional.of(("0x" + Keys.getAddress(key)).toLowerCase(Locale.ROOT));
        } catch (SignatureException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String typeString(String name, List<Field> fields) {
        StringBuilder out = new StringBuilder(name).append('(');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append(',');
            out.append(fields.get(i).type()).append(' ').append(fields.get(i).name());
        }
        return out.append(')').toString();
    }

    private static List<Map<String, String>> fieldsJson(List<Field> fields) {
        return fields.stream().map(f -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", f.name());
            entry.put("type", f.type());
            return entry;
        }).toList();
    }

    private static byte[] string(String value) {
        return Hash.sha3(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] address(String value) {
        return Numeric.toBytesPadded(Numeric.toBigInt(value), 32);
    }

    private static byte[] uint(BigInteger value) {
        return Numeric.toBytesPadded(value, 32);
    }
}
