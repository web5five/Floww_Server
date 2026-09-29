package com.floww.server.auth.wallet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WalletSigninConfigTest {
    private static final String KEY = "test-only-signing-key-0123456789abcdef";
    @Test
    void trustedOriginAndChainsAreRequiredWhenEnabled() {
        WalletSigninConfig local = new WalletSigninConfig("http://127.0.0.1:8080", "11155111", KEY);
        assertEquals("127.0.0.1:8080", local.domain);
        assertTrue(local.chainIds.contains(11155111L));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("", "11155111", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("http://floww.example", "11155111", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example@evil.example", "11155111", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example/path", "11155111", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example", "", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example", "0", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example", "-1", KEY));
        assertThrows(IllegalStateException.class, () -> new WalletSigninConfig("https://floww.example", "11155111", ""));
    }
}
