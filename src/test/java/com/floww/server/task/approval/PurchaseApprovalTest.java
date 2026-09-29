package com.floww.server.task.approval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/** Issue #35: EIP-712 digest가 표준 인코더와 같고, 서명자 복구가 안전하게 동작하는지 확인한다. */
class PurchaseApprovalTest {
    private static final long SEPOLIA = 11155111L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PurchaseApproval.Message message(String nonceSuffix) {
        return new PurchaseApproval.Message("2f9c1d4e-8a7b-4c6d-9e0f-1a2b3c4d5e6f",
                "7b1e2c7a-3f7e-4b4e-9d9a-0c1f2a3b4c5d", 1, "pharmacy-a", "qt_a_0123456789abcdef",
                "0x00000000000000000000000000000000f10aa001", "0x84b494ff145a545d286321691a9b4febe6947d6a",
                new BigInteger("23500000"), new BigInteger("60000000"), 1790000000L,
                "0x" + "ab".repeat(31) + nonceSuffix);
    }

    @Test
    void web3jEncoderMatchesEip712SpecMailVector() throws Exception {
        // EIP-712 명세의 예제 — 교차 검증에 쓰는 web3j 인코더 자체가 표준과 같은지 먼저 확인한다.
        String mail = """
                {"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},
                {"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],
                "Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],
                "Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},
                "primaryType":"Mail","domain":{"name":"Ether Mail","version":"1","chainId":1,
                "verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},
                "message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},
                "to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}
                """;
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2",
                Numeric.toHexString(new StructuredDataEncoder(mail).hashStructuredData()));
    }

    @Test
    void digestMatchesStandardEncoderForTheTypedDataSentToWallets() throws Exception {
        PurchaseApproval.Message m = message("01");
        String typedData = JSON.writeValueAsString(PurchaseApproval.typedData(SEPOLIA, m));
        assertArrayEquals(new StructuredDataEncoder(typedData).hashStructuredData(),
                PurchaseApproval.digest(SEPOLIA, m));
    }

    @Test
    void everySignedFieldChangesTheDigest() {
        byte[] base = PurchaseApproval.digest(SEPOLIA, message("01"));
        assertNotEquals(Numeric.toHexString(base), Numeric.toHexString(PurchaseApproval.digest(SEPOLIA, message("02"))));
        assertNotEquals(Numeric.toHexString(base), Numeric.toHexString(PurchaseApproval.digest(1L, message("01"))));
        PurchaseApproval.Message m = message("01");
        PurchaseApproval.Message otherRecipient = new PurchaseApproval.Message(m.taskId(), m.mandateId(), m.version(),
                m.merchantId(), m.quoteId(), "0x00000000000000000000000000000000badc0de3", m.tokenAddress(),
                m.amountBaseUnits(), m.maxAmountBaseUnits(), m.expiresAt(), m.nonce());
        assertNotEquals(Numeric.toHexString(base), PurchaseApproval.digestHex(SEPOLIA, otherRecipient));
        PurchaseApproval.Message nextVersion = new PurchaseApproval.Message(m.taskId(), m.mandateId(), 2,
                m.merchantId(), m.quoteId(), m.recipientAddress(), m.tokenAddress(), m.amountBaseUnits(),
                m.maxAmountBaseUnits(), m.expiresAt(), m.nonce());
        assertNotEquals(Numeric.toHexString(base), PurchaseApproval.digestHex(SEPOLIA, nextVersion));
    }

    @Test
    void recoversSignerFromWalletStyleSignatureWithEitherRecoveryIdEncoding() throws Exception {
        Credentials wallet = Credentials.create(Keys.createEcKeyPair());
        byte[] digest = PurchaseApproval.digest(SEPOLIA, message("01"));
        Sign.SignatureData signed = Sign.signMessage(digest, wallet.getEcKeyPair(), false);
        String expected = wallet.getAddress().toLowerCase(Locale.ROOT);

        assertEquals(expected, PurchaseApproval.recoverSigner(digest, signature(signed, signed.getV()[0])).orElseThrow());
        assertEquals(expected, PurchaseApproval.recoverSigner(digest,
                signature(signed, (byte) (signed.getV()[0] - 27))).orElseThrow());
        assertNotEquals(expected, PurchaseApproval.recoverSigner(PurchaseApproval.digest(SEPOLIA, message("02")),
                signature(signed, signed.getV()[0])).orElse(""));
    }

    @Test
    void rejectsMalformedAndHighSSignatures() throws Exception {
        byte[] digest = PurchaseApproval.digest(SEPOLIA, message("01"));
        assertTrue(PurchaseApproval.recoverSigner(digest, null).isEmpty());
        assertTrue(PurchaseApproval.recoverSigner(digest, "0x1234").isEmpty());
        assertTrue(PurchaseApproval.recoverSigner(digest, "0x" + "11".repeat(64) + "1d").isEmpty());

        Credentials wallet = Credentials.create(Keys.createEcKeyPair());
        Sign.SignatureData signed = Sign.signMessage(digest, wallet.getEcKeyPair(), false);
        BigInteger n = new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);
        byte[] highS = Numeric.toBytesPadded(n.subtract(new BigInteger(1, signed.getS())), 32);
        byte flipped = (byte) (signed.getV()[0] == 27 ? 28 : 27);
        assertTrue(PurchaseApproval.recoverSigner(digest,
                signature(new Sign.SignatureData(flipped, signed.getR(), highS), flipped)).isEmpty());
    }

    @Test
    void messageRejectsUnnormalizedAddressesAndNonPositiveAmounts() {
        PurchaseApproval.Message m = message("01");
        assertThrows(IllegalArgumentException.class, () -> new PurchaseApproval.Message(m.taskId(), m.mandateId(), 1,
                m.merchantId(), m.quoteId(), "0x00000000000000000000000000000000F10AA001", m.tokenAddress(),
                m.amountBaseUnits(), m.maxAmountBaseUnits(), m.expiresAt(), m.nonce()));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseApproval.Message(m.taskId(), m.mandateId(), 1,
                m.merchantId(), m.quoteId(), m.recipientAddress(), m.tokenAddress(), BigInteger.ZERO,
                m.maxAmountBaseUnits(), m.expiresAt(), m.nonce()));
    }

    private static String signature(Sign.SignatureData data, byte v) {
        byte[] out = new byte[65];
        System.arraycopy(data.getR(), 0, out, 0, 32);
        System.arraycopy(data.getS(), 0, out, 32, 32);
        out[64] = v;
        return Numeric.toHexString(out);
    }
}
