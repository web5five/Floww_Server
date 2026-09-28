package com.floww.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MerchantGatewayTest {
    @Test void absentMerchantFailsClosed() {
        MerchantGateway gateway = new MerchantGateway(new ObjectMapper(), "");
        assertFalse(gateway.available());
        assertEquals("MERCHANT_NOT_CONFIGURED",
                assertThrows(MerchantGateway.Failure.class, () -> gateway.searchOffers("item-1")).code());
    }
    @Test void rejectsNonLoopbackTestMerchant() {
        assertThrows(IllegalArgumentException.class,
                () -> new MerchantGateway(new ObjectMapper(), "https://merchant.example"));
    }
}
