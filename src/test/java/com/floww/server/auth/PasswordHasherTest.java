package com.floww.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.floww.server.auth.infrastructure.PasswordHasher;
import org.junit.jupiter.api.Test;

/** Issue #22: BCrypt 해시·검증. 테스트 속도를 위해 cost 4를 쓴다. */
class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher(4);

    @Test
    void hashIsSaltedBcryptAndVerifies() {
        String first = hasher.hash("password1");
        String second = hasher.hash("password1");

        assertTrue(first.startsWith("$2"));
        assertFalse(first.contains("password1"));
        assertNotEquals(first, second);
        assertTrue(hasher.matches("password1", first));
        assertFalse(hasher.matches("password2", first));
    }

    @Test
    void missingUserNeverMatches() {
        assertFalse(hasher.matches("password1", null));
        assertFalse(hasher.matches(null, null));
    }

    @Test
    void inputOver72BytesNeverMatches() {
        // BCrypt는 72바이트 이후를 무시한다. 72바이트 비밀번호 + 추가 문자가 통과하면 안 된다.
        String exactly72 = "a".repeat(72);
        String hash = hasher.hash(exactly72);
        assertTrue(hasher.matches(exactly72, hash));
        assertFalse(hasher.matches(exactly72 + "anything", hash));
    }
}