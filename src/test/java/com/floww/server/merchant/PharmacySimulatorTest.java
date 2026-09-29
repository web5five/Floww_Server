package com.floww.server.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Issue #33 (#17): 약국 3곳 시뮬레이터의 결정성·데모 후보 구성을 확인한다. */
class PharmacySimulatorTest {
    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00.750Z");
    private static final BigInteger MANDATE_60_FUSDC = new BigInteger("60000000");

    private final MerchantRegistry registry = new MerchantRegistry(
            "0x00000000000000000000000000000000f10aa001",
            "0x00000000000000000000000000000000f10aa002",
            "0x00000000000000000000000000000000f10aa003");
    private final SettlementAsset asset = new SettlementAsset(11155111L,
            "0x84B494ff145a545D286321691a9B4Febe6947D6A", 6, "fUSDC");
    private final PharmacySimulator simulator = new PharmacySimulator(registry, asset);

    @Test
    void exactlyThreeStablePharmaciesWithUniqueRecipients() {
        List<MerchantRegistry.Merchant> merchants = registry.active();
        assertEquals(List.of("pharmacy-a", "pharmacy-b", "pharmacy-c"),
                merchants.stream().map(MerchantRegistry.Merchant::merchantId).toList());
        Set<String> recipients = new HashSet<>();
        merchants.forEach(m -> recipients.add(m.recipientAddress()));
        assertEquals(3, recipients.size());
    }

    @Test
    void registryRejectsDuplicateOrMalformedRecipients() {
        assertThrows(IllegalStateException.class, () -> new MerchantRegistry(
                "0x00000000000000000000000000000000f10aa001",
                "0x00000000000000000000000000000000F10AA001",
                "0x00000000000000000000000000000000f10aa003"));
        assertThrows(IllegalStateException.class, () -> new MerchantRegistry(
                "not-an-address", "0x00000000000000000000000000000000f10aa002",
                "0x00000000000000000000000000000000f10aa003"));
    }

    @Test
    void quotesCarryAvailabilityPriceDeliveryFeeAndTotalInBaseUnits() {
        List<PharmacySimulator.Quote> quotes = simulator.quotes("task-1", PharmacySimulator.ACETAMINOPHEN, NOW);
        assertEquals(3, quotes.size());
        for (PharmacySimulator.Quote quote : quotes) {
            assertEquals(quote.itemAmountBaseUnits().add(quote.deliveryFeeBaseUnits()), quote.totalAmountBaseUnits());
            assertEquals(11155111L, quote.chainId());
            assertEquals("0x84b494ff145a545d286321691a9b4febe6947d6a", quote.tokenAddress());
            assertEquals(6, quote.tokenDecimals());
            assertEquals(Instant.parse("2026-09-30T01:00:00Z"), quote.quotedAt());
            assertEquals(quote.quotedAt().plus(PharmacySimulator.QUOTE_TTL), quote.expiresAt());
        }
        assertEquals("23500000", quotes.get(0).totalAmountBaseUnits().toString());
        assertEquals("64000000", quotes.get(1).totalAmountBaseUnits().toString());
        assertEquals("19000000", quotes.get(2).totalAmountBaseUnits().toString());

        List<PharmacySimulator.Quote> ibuprofen = simulator.quotes("task-1", PharmacySimulator.IBUPROFEN, NOW);
        assertFalse(ibuprofen.get(1).inStock());
        assertTrue(simulator.quotes("task-1", "unknown-item", NOW).isEmpty());
    }

    @Test
    void demoHasOneOptionWithinMandateAndTwoBlockedCases() {
        List<PharmacySimulator.Quote> quotes = simulator.quotes("task-1", PharmacySimulator.ACETAMINOPHEN, NOW);
        PharmacySimulator.Quote a = quotes.get(0), b = quotes.get(1), c = quotes.get(2);
        // A: 레지스트리 주소로 받고 60 fUSDC 한도 안
        assertEquals(registry.find("pharmacy-a").orElseThrow().recipientAddress(), a.payToAddress());
        assertTrue(a.totalAmountBaseUnits().compareTo(MANDATE_60_FUSDC) <= 0);
        // B: 한도 초과 (시뮬레이터는 위임을 검사하지 않고 그대로 돌려준다)
        assertTrue(b.totalAmountBaseUnits().compareTo(MANDATE_60_FUSDC) > 0);
        // C: 가장 싸지만 payTo가 레지스트리와 다르다
        assertNotEquals(registry.find("pharmacy-c").orElseThrow().recipientAddress(), c.payToAddress());
        assertEquals(PharmacySimulator.PHARMACY_C_QUOTED_PAY_TO, c.payToAddress());
    }

    @Test
    void quoteOrderAndFulfillmentIdsAreDeterministic() {
        List<PharmacySimulator.Quote> first = simulator.quotes("task-1", PharmacySimulator.ACETAMINOPHEN, NOW);
        List<PharmacySimulator.Quote> again = simulator.quotes("task-1", PharmacySimulator.ACETAMINOPHEN,
                NOW.plusMillis(100));
        assertEquals(first, again);
        assertNotEquals(first.get(0).quoteId(),
                simulator.quotes("task-2", PharmacySimulator.ACETAMINOPHEN, NOW).get(0).quoteId());
        assertTrue(first.get(0).quoteId().startsWith("qt_a_"));

        PharmacySimulator.Order order = simulator.placeOrder("pharmacy-a", first.get(0).quoteId());
        assertEquals(order, simulator.placeOrder("pharmacy-a", first.get(0).quoteId()));
        assertTrue(order.orderId().startsWith("ord_a_"));
        assertEquals("ACCEPTED", order.status());

        PharmacySimulator.Fulfillment fulfillment = simulator.fulfill(order.orderId());
        assertEquals(fulfillment, simulator.fulfill(order.orderId()));
        assertEquals("DELIVERED", fulfillment.status());
    }
}
