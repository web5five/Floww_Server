package com.floww.server.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #13: 에러 코드 표가 SA 7장 필수 요소(한·영 메시지, 재시도 여부, HTTP 상태)를 모두 갖는지 확인한다. */
class ErrorCodeTest {

    @Test
    void everyCodeHasBilingualMessages() {
        for (ErrorCode code : ErrorCode.values()) {
            assertFalse(code.messageKo().isBlank(), code + " ko");
            assertFalse(code.messageEn().isBlank(), code + " en");
        }
    }

    @Test
    void onlyHttpCodesCarryAnHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.isHttp()) assertNotNull(code.httpStatus(), code.name());
            else assertNull(code.httpStatus(), code.name());
        }
    }

    @Test
    void existingPublicStatusesAreUnchanged() {
        // 기존 응답 상태코드는 프론트·테스트가 의존하므로 유지한다.
        assertEquals(400, ErrorCode.INVALID_INPUT.httpStatus().value());
        assertEquals(401, ErrorCode.UNAUTHORIZED.httpStatus().value());
        assertEquals(404, ErrorCode.EXECUTION_NOT_FOUND.httpStatus().value());
        assertEquals(409, ErrorCode.IDEMPOTENCY_CONFLICT.httpStatus().value());
        assertEquals(409, ErrorCode.ALREADY_RUN.httpStatus().value());
    }

    @Test
    void policyRejectionsForChallengeBAreNotRetryable() {
        assertFalse(ErrorCode.BUDGET_EXCEEDED.retryable());
        assertFalse(ErrorCode.RECIPIENT_NOT_ALLOWED.retryable());
        assertFalse(ErrorCode.MANDATE_EXPIRED.retryable());
    }

    @Test
    void fromCodeMapsStoredStringsIncludingDynamicHttpCodes() {
        assertEquals(Optional.of(ErrorCode.BUDGET_EXCEEDED), ErrorCode.fromCode("BUDGET_EXCEEDED"));
        assertEquals(Optional.of(ErrorCode.MERCHANT_HTTP_ERROR), ErrorCode.fromCode("MERCHANT_HTTP_502"));
        assertEquals(Optional.of(ErrorCode.PROVIDER_HTTP_ERROR), ErrorCode.fromCode("PROVIDER_HTTP_401"));
        assertTrue(ErrorCode.fromCode("NOT_A_REAL_CODE").isEmpty());
        assertTrue(ErrorCode.fromCode(null).isEmpty());
    }

    @Test
    void apiExceptionRejectsExecutionOutcomeCodes() {
        assertThrows(IllegalArgumentException.class, () -> new ApiException(ErrorCode.BUDGET_EXCEEDED));
    }
}
