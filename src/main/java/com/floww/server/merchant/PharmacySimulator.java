package com.floww.server.merchant;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 결정적 약국 3곳 시뮬레이터 — Issue #33 (#17).
 *
 * <p>외부 판매자 역할만 한다. Floww의 위임(예산·수취인·기한)을 검사하지 않으며, 예산을 넘는 견적이나
 * 레지스트리와 다른 payTo를 가진 견적도 그대로 돌려준다. 차단은 서버 정책(TaskPolicy)의 몫이다.
 *
 * <p>같은 입력(requestRef, itemId, 견적 시각)이면 항상 같은 quoteId·orderId·이행 결과를 만든다.
 * 실제 약국·결제·배송이 아니다.
 */
@Component
public class PharmacySimulator {
    public static final String PHARMACY_A = "pharmacy-a";
    public static final String PHARMACY_B = "pharmacy-b";
    public static final String PHARMACY_C = "pharmacy-c";
    public static final String ACETAMINOPHEN = "acetaminophen-500mg-10";
    public static final String IBUPROFEN = "ibuprofen-200mg-20";
    public static final Duration QUOTE_TTL = Duration.ofMinutes(15);
    /** pharmacy-c가 견적에 넣는 payTo. 레지스트리 주소와 달라 RECIPIENT_NOT_ALLOWED 데모에 쓴다. */
    public static final String PHARMACY_C_QUOTED_PAY_TO = "0x00000000000000000000000000000000badc0de3";
    public static final String EVIDENCE_MODE = "local_pharmacy_simulator";

    public record Quote(String merchantId, String quoteId, String itemId, String itemName, int quantity,
                        boolean inStock, BigInteger itemAmountBaseUnits, BigInteger deliveryFeeBaseUnits,
                        BigInteger totalAmountBaseUnits, long chainId, String tokenAddress, int tokenDecimals,
                        String payToAddress, Instant quotedAt, Instant expiresAt,
                        Instant promisedFulfillmentAt) { }
    public record Order(String merchantId, String orderId, String quoteId, String status) { }
    public record Fulfillment(String orderId, String status, String reference) { }

    private record Medication(String name, int quantity) { }
    private record Listing(boolean inStock, long itemAmount, long deliveryFee, Duration fulfillment) { }

    private static final Map<String, Medication> MEDICATIONS = Map.of(
            ACETAMINOPHEN, new Medication("Acetaminophen 500mg, 10 tablets", 1),
            IBUPROFEN, new Medication("Ibuprofen 200mg, 20 tablets", 1));

    /** 금액은 fUSDC(decimals=6) 최소 단위. 20_500000 = 20.5 fUSDC. */
    private static final Map<String, Map<String, Listing>> CATALOG = Map.of(
            PHARMACY_A, Map.of(
                    ACETAMINOPHEN, new Listing(true, 20_500000L, 3_000000L, Duration.ofHours(2)),
                    IBUPROFEN, new Listing(true, 12_000000L, 3_000000L, Duration.ofHours(2))),
            PHARMACY_B, Map.of(
                    ACETAMINOPHEN, new Listing(true, 52_000000L, 12_000000L, Duration.ofHours(4)),
                    IBUPROFEN, new Listing(false, 11_000000L, 12_000000L, Duration.ofHours(4))),
            PHARMACY_C, Map.of(
                    ACETAMINOPHEN, new Listing(true, 17_000000L, 2_000000L, Duration.ofHours(1)),
                    IBUPROFEN, new Listing(true, 9_000000L, 2_000000L, Duration.ofHours(1))));

    private final MerchantRegistry registry;
    private final SettlementAsset asset;

    public PharmacySimulator(MerchantRegistry registry, SettlementAsset asset) {
        this.registry = registry;
        this.asset = asset;
    }

    public String evidenceMode() { return EVIDENCE_MODE; }

    /** 해당 상품을 취급하는 약국의 견적. 모르는 상품이면 빈 목록. 재고 없는 견적도 inStock=false로 포함한다. */
    public List<Quote> quotes(String requestRef, String itemId, Instant now) {
        Medication medication = MEDICATIONS.get(itemId);
        if (medication == null) return List.of();
        Instant quotedAt = now.truncatedTo(ChronoUnit.SECONDS);
        List<Quote> quotes = new ArrayList<>();
        for (String merchantId : List.of(PHARMACY_A, PHARMACY_B, PHARMACY_C)) {
            Listing listing = CATALOG.get(merchantId).get(itemId);
            if (listing == null) continue;
            BigInteger item = BigInteger.valueOf(listing.itemAmount());
            BigInteger fee = BigInteger.valueOf(listing.deliveryFee());
            String quoteId = "qt_" + suffix(merchantId) + "_"
                    + hash(requestRef + "|" + merchantId + "|" + itemId + "|" + quotedAt.getEpochSecond());
            quotes.add(new Quote(merchantId, quoteId, itemId, medication.name(), medication.quantity(),
                    listing.inStock(), item, fee, item.add(fee), asset.chainId(), asset.tokenAddress(),
                    asset.decimals(), payTo(merchantId), quotedAt, quotedAt.plus(QUOTE_TTL),
                    quotedAt.plus(listing.fulfillment())));
        }
        return List.copyOf(quotes);
    }

    /** 주문 접수만 한다. 결제·서명·배송을 실행하지 않는다. 같은 quoteId면 같은 orderId. */
    public Order placeOrder(String merchantId, String quoteId) {
        return new Order(merchantId, "ord_" + suffix(merchantId) + "_" + hash("order|" + quoteId),
                quoteId, "ACCEPTED");
    }

    /** 결정적 이행 결과. 지급 확정 후에만 호출해야 하며, 호출 자체가 지급 사실을 만들지 않는다. */
    public Fulfillment fulfill(String orderId) {
        return new Fulfillment(orderId, "DELIVERED", "ful_" + hash("fulfill|" + orderId));
    }

    private String payTo(String merchantId) {
        if (PHARMACY_C.equals(merchantId)) return PHARMACY_C_QUOTED_PAY_TO;
        return registry.find(merchantId).orElseThrow().recipientAddress();
    }

    private static String suffix(String merchantId) {
        return merchantId.substring(merchantId.length() - 1);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
