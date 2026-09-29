package com.floww.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #22: UserRepository를 실제 PostgreSQL(Flyway V2 적용)에 대해 확인한다.
 *
 * <p>@Transactional: 테스트마다 롤백해 로컬·CI DB에 사용자 행을 남기지 않는다.
 * 다른 테스트·로컬 데이터와 겹치지 않도록 이메일에 무작위 값을 붙인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"floww.auth.alice-token=ci-only-alice-token-0001",
                "floww.auth.bob-token=ci-only-bob-token-00002"})
@Transactional
class UserRepositoryTest {
    @Autowired UserRepository users;

    private static String email() {
        return "repo-" + UUID.randomUUID() + "@test.floww";
    }

    @Test
    void saveStoresActiveEmailUserAndReturnsRow() {
        String email = email();
        User saved = users.save(email, "$2a$10$fixture-hash", UserRole.USER, "홍길동");

        assertEquals(email, saved.email());
        assertEquals(UserRole.USER, saved.role());
        assertEquals(UserStatus.ACTIVE, saved.status());
        assertEquals(AuthProvider.EMAIL, saved.provider());
        assertEquals("홍길동", saved.displayName());
        assertEquals(saved, users.findById(saved.id()).orElseThrow());
        assertEquals(saved, users.findByEmail(email).orElseThrow());
    }

    @Test
    void duplicateEmailIsConflictNotDatabaseError() {
        String email = email();
        users.save(email, "$2a$10$first", UserRole.USER, null);

        ApiException e = assertThrows(ApiException.class,
                () -> users.save(email, "$2a$10$second", UserRole.USER, null));
        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, e.errorCode());
    }

    @Test
    void lookupsReturnEmptyWhenMissing() {
        assertTrue(users.findById(UUID.randomUUID()).isEmpty());
        assertTrue(users.findByEmail(email()).isEmpty());
        assertFalse(users.existsByEmail(email()));
    }

    @Test
    void existsQueries() {
        String email = email();
        users.save(email, "$2a$10$fixture-hash", UserRole.ADMIN, null);
        assertTrue(users.existsByEmail(email));
        assertTrue(users.existsByRole(UserRole.ADMIN));
    }

    @Test
    void constraintViolationDoesNotLeakRowDetails() {
        // 정규화되지 않은 이메일은 DB CHECK가 거절한다. 예외에 이메일·해시·원인 예외가 없어야 한다.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> users.save("Upper-" + UUID.randomUUID() + "@test.floww", "$2a$10$secret-hash", UserRole.USER, null));
        assertFalse(e.getMessage().contains("secret-hash"));
        assertFalse(e.getMessage().contains("@test.floww"));
        assertNull(e.getCause());
    }
}