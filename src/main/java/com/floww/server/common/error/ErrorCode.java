package com.floww.server.common.error;

import java.util.Optional;

import org.junit.Test;
import org.springframework.http.HttpStatus;

import static org.junit.Assert.*;

/**
 * Floww 서버 공통 에러 코드 — Issue #13 (Common: Error Handling).
 *
 * <p><b>SA 7장 규칙 (작업 흐름·API·데이터 계약)</b>
 * <ul>
 *   <li>오류 응답에는 기계 판독 {@code reasonCode}, 사용자용 한·영 설명, {@code taskId}/{@code attemptId},
 *       재시도 가능 여부를 둔다.</li>
 *   <li>HTTP 상태·재시도 규칙은 백엔드 담당(최리아)이 확정한다. 이 enum이 그 규칙의 단일 원본이며,
 *       코드별 HTTP 상태와 retryable 값을 바꿀 때는 이 파일과 docs의 에러 계약 표를 함께 고친다.</li>
 *   <li>상태·오류 코드는 공통 OpenAPI/JSON 예시로 고정한다. 이미 공개된 코드 이름(예: QUOTE_STALE)은
 *       프론트·증거 문서가 의존하므로 임의로 바꾸지 않는다.</li>
 * </ul>
 *
 * <p><b>retryable 정의</b>: 같은 요청을 나중에 다시 보내거나 새 실행을 만들면 성공할 <i>수</i> 있는지 여부.
 * <ul>
 *   <li>SA 6장 동시성·중복: 재시도는 반드시 같은 {@code Idempotency-Key}로 한다.
 *       retryable=true가 새 키로 새 요청을 만들어도 된다는 뜻이 아니다.</li>
 *   <li>SA 6장·7.2: 결과 불명(예: 향후 PAYMENT_UNKNOWN)은 조회 전 재지급하지 않는다.
 *       지급 관련 코드를 추가할 때 "결과 불명"은 retryable=false로 두고 상태 조회 경로를 안내한다.</li>
 *   <li>정책 차단(REJECTED) 중 모델 이상 동작 코드는 보안 차단이므로 자동 재시도하지 않도록 false로 둔다.</li>
 * </ul>
 *
 * <p><b>카테고리</b>
 * <ul>
 *   <li>{@link Category#HTTP}: {@link ApiException}으로 던지고 4xx/5xx로 응답한다. httpStatus 필수.</li>
 *   <li>{@link Category#REJECTED}: 실행 레코드에 status=REJECTED로 저장되는 정책 차단 사유. HTTP 응답 코드가 아니다.</li>
 *   <li>{@link Category#FAILED}: 실행 레코드에 status=FAILED로 저장되는 실행 실패 사유. HTTP 응답 코드가 아니다.</li>
 * </ul>
 *
 * <p>상태: 제안(미확정). 팀 검토 후 확정한다.
 */
public enum ErrorCode {

    // ───────────────────────── HTTP (ApiException) ─────────────────────────
    INVALID_INPUT(Category.HTTP, HttpStatus.BAD_REQUEST, false,
            "요청 값이 올바르지 않습니다", "Invalid request input"),
    INVALID_CURSOR(Category.HTTP, HttpStatus.BAD_REQUEST, false,
            "조회 커서가 올바르지 않습니다", "Invalid cursor"),
    INVALID_LIMIT(Category.HTTP, HttpStatus.BAD_REQUEST, false,
            "조회 개수는 1–100이어야 합니다", "Limit must be 1–100"),
    MALFORMED_JSON(Category.HTTP, HttpStatus.BAD_REQUEST, false,
            "요청 본문을 해석할 수 없습니다", "Malformed request body"),
    UNAUTHORIZED(Category.HTTP, HttpStatus.UNAUTHORIZED, false,
            "인증이 필요합니다", "Authentication required"),

    // ─────────────── AUTH (Issue #22, AUTH-00·05·06·11) ───────────────
    /**
     * 로그인 실패 공통 코드. 이메일 없음, 비밀번호 틀림, 어드민 경로에 USER 계정 로그인을
     * 모두 이 코드 하나로 응답해 계정 존재 여부·권한을 드러내지 않는다.
     * 토큰 없음·만료·위조는 {@link #UNAUTHORIZED}를 쓴다.
     */
    INVALID_CREDENTIALS(Category.HTTP, HttpStatus.UNAUTHORIZED, false,
            "이메일 또는 비밀번호가 올바르지 않습니다", "Invalid email or password"),
    /** 회원가입 시 이미 가입된 이메일 (소문자 정규화 후 비교). */
    EMAIL_ALREADY_EXISTS(Category.HTTP, HttpStatus.CONFLICT, false,
            "이미 가입된 이메일입니다", "Email already registered"),
    /** 비밀번호 규칙 위반. 규칙은 팀 합의 후 확정한다. 응답·로그에 비밀번호 원문을 넣지 않는다. */
    WEAK_PASSWORD(Category.HTTP, HttpStatus.BAD_REQUEST, false,
            "비밀번호가 규칙에 맞지 않습니다", "Password does not meet requirements"),
    /** 인증은 되었지만 권한 부족. 예: client 토큰(또는 role≠ADMIN)으로 /api/v1/admin/** 호출. */
    FORBIDDEN(Category.HTTP, HttpStatus.FORBIDDEN, false,
            "접근 권한이 없습니다", "Access denied"),
    /** 정지된 계정. 비밀번호 검증을 통과한 뒤에만 응답한다 (계정 존재 여부 노출 방지). */
    USER_SUSPENDED(Category.HTTP, HttpStatus.FORBIDDEN, false,
            "정지된 계정입니다", "Account suspended"),

    /**
     * SA 6장 감사·데이터 접근: 작업 소유자는 자기 기록만 조회한다.
     * 타인 소유 실행도 403이 아니라 404로 응답해 존재 여부를 드러내지 않는다.
     */
    EXECUTION_NOT_FOUND(Category.HTTP, HttpStatus.NOT_FOUND, false,
            "실행을 찾을 수 없습니다", "Execution not found"),
    ROUTE_NOT_FOUND(Category.HTTP, HttpStatus.NOT_FOUND, false,
            "존재하지 않는 경로입니다", "Route not found"),
    METHOD_NOT_ALLOWED(Category.HTTP, HttpStatus.METHOD_NOT_ALLOWED, false,
            "허용되지 않는 메서드입니다", "Method not allowed"),
    /** SA 6장 동시성·중복: 같은 idempotencyKey에 다른 요청 본문이 오면 거절한다. */
    IDEMPOTENCY_CONFLICT(Category.HTTP, HttpStatus.CONFLICT, false,
            "같은 멱등 키로 다른 요청이 들어왔습니다", "Idempotency key reused with different request"),
    ALREADY_RUN(Category.HTTP, HttpStatus.CONFLICT, false,
            "이미 실행된 작업입니다", "Execution already started"),
    NOT_RUNNING(Category.HTTP, HttpStatus.CONFLICT, false,
            "실행 중인 상태가 아닙니다", "Execution is not running"),
    /** 현재 공통 경로에서는 발생하지 않는다. aidraft 경로와 이름을 맞추기 위해 둔다. */
    REQUEST_TOO_LARGE(Category.HTTP, HttpStatus.PAYLOAD_TOO_LARGE, false,
            "요청이 너무 큽니다", "Request too large"),
    UNSUPPORTED_MEDIA_TYPE(Category.HTTP, HttpStatus.UNSUPPORTED_MEDIA_TYPE, false,
            "지원하지 않는 Content-Type입니다", "Unsupported media type"),
    /** 응답에 예외 메시지·스택트레이스를 넣지 않는다(SA 6장: 비밀정보·불필요한 내부 정보 비노출). */
    INTERNAL_ERROR(Category.HTTP, HttpStatus.INTERNAL_SERVER_ERROR, true,
            "서버 오류가 발생했습니다", "Internal server error"),

    // ─────────────── REJECTED (ExecutionService.reject, 정책 차단) ───────────────
    // SA 2장 Challenge B: 범위 밖 시도를 차단하고 이유·실행 기록을 남긴다.
    // SA 6장: 필수 검증 실패 시 서명하지 않는다 (paymentStatus = NOT_ATTEMPTED).
    MANDATE_EXPIRED(Category.REJECTED, null, false,
            "위임 기한이 만료되었습니다", "Mandate expired"),
    BUDGET_EXCEEDED(Category.REJECTED, null, false,
            "승인한 예산을 초과합니다", "Budget exceeded"),
    RECIPIENT_NOT_ALLOWED(Category.REJECTED, null, false,
            "허용되지 않은 수취인입니다", "Recipient not allowed"),
    ITEM_NOT_ALLOWED(Category.REJECTED, null, false,
            "허용되지 않은 상품입니다", "Item not allowed"),
    CURRENCY_MISMATCH(Category.REJECTED, null, false,
            "결제 통화가 위임과 다릅니다", "Currency mismatch"),
    /** SA 예시의 QUOTE_EXPIRED와 같은 의미. 공개 계약에 나간 이름이라 유지한다. */
    QUOTE_STALE(Category.REJECTED, null, true,
            "견적이 만료되었습니다", "Quote expired"),
    // 모델 이상 동작 — SA 5장: 모델은 제안자일 뿐 위임 조건 변경·지급 권한이 없다. 자동 재시도 금지.
    MODEL_MISMATCH(Category.REJECTED, null, false,
            "허용된 모델의 응답이 아닙니다", "Unexpected model"),
    UNKNOWN_OFFER_ID(Category.REJECTED, null, false,
            "알 수 없는 상품 후보입니다", "Unknown offer"),
    UNKNOWN_QUOTE_ID(Category.REJECTED, null, false,
            "알 수 없는 견적입니다", "Unknown quote"),
    QUOTE_OFFER_MISMATCH(Category.REJECTED, null, false,
            "견적과 상품 후보가 일치하지 않습니다", "Quote/offer mismatch"),
    TOOL_NOT_ALLOWED(Category.REJECTED, null, false,
            "허용되지 않은 도구 호출입니다", "Tool not allowed"),
    INVALID_TOOL_ARGUMENTS(Category.REJECTED, null, false,
            "도구 인수가 올바르지 않습니다", "Invalid tool arguments"),
    DUPLICATE_TOOL_CALL_ID(Category.REJECTED, null, false,
            "중복된 도구 호출입니다", "Duplicate tool call"),
    EARLY_MODEL_STOP(Category.REJECTED, null, false,
            "AI가 절차를 끝내지 않고 중단했습니다", "Model stopped early"),
    /**
     * TODO(#13): ExecutionService에서 reject(70행)와 fail(148행) 양쪽으로 쓰인다.
     * enum은 카테고리를 하나만 가질 수 있어 REJECTED로 두었다. 코드 쪽 통일이 필요하다.
     */
    TOOL_LOOP_LIMIT(Category.REJECTED, null, false,
            "도구 호출 횟수 한도를 넘었습니다", "Tool loop limit reached"),

    // ─────────────── FAILED (ExecutionService.fail, 실행 실패) ───────────────
    KILN_NOT_CONFIGURED(Category.FAILED, null, false,
            "AI 제공자가 설정되지 않았습니다", "AI provider not configured"),
    MERCHANT_NOT_CONFIGURED(Category.FAILED, null, false,
            "상점이 설정되지 않았습니다", "Merchant not configured"),
    /** SA 5.1: 호출 수·총 실행 시간 상한을 기록한다. 시간 초과를 새 지급으로 바꾸지 않는다. */
    RUN_TIME_LIMIT(Category.FAILED, null, true,
            "실행 시간 한도를 초과했습니다", "Run time limit exceeded"),
    PROVIDER_UNAVAILABLE(Category.FAILED, null, true,
            "AI 제공자에 연결할 수 없습니다", "AI provider unavailable"),
    PROVIDER_INTERRUPTED(Category.FAILED, null, true,
            "AI 호출이 중단되었습니다", "AI call interrupted"),
    /**
     * KilnClient는 현재 "PROVIDER_HTTP_" + status 형태의 동적 코드를 만든다.
     * {@link #fromCode(String)}가 이를 이 값으로 매핑한다. MERCHANT_HTTP_ERROR와 같은 이유로 보수적으로 false.
     */
    PROVIDER_HTTP_ERROR(Category.FAILED, null, false,
            "AI 제공자가 오류를 반환했습니다", "AI provider returned an error"),
    /** Kiln이 403 + "Error 1010" 본문으로 접근을 차단한 경우. 설정·네트워크 문제라 재시도로 해결되지 않는다. */
    PROVIDER_ACCESS_BLOCK_1010(Category.FAILED, null, false,
            "AI 제공자 접근이 차단되었습니다", "AI provider access blocked"),
    PROVIDER_REQUEST_INVALID(Category.FAILED, null, false,
            "AI 요청 구성이 잘못되었습니다", "Invalid provider request"),
    MODEL_OUTPUT_INVALID(Category.FAILED, null, true,
            "AI 응답 형식이 올바르지 않습니다", "Invalid model output"),
    MODEL_OUTPUT_TRUNCATED(Category.FAILED, null, true,
            "AI 응답이 잘렸습니다", "Model output truncated"),
    MODEL_CALL_FAILED(Category.FAILED, null, false,
            "AI 호출 중 알 수 없는 오류가 발생했습니다", "Model call failed"),
    MERCHANT_UNAVAILABLE(Category.FAILED, null, true,
            "상점에 연결할 수 없습니다", "Merchant unavailable"),
    MERCHANT_INTERRUPTED(Category.FAILED, null, true,
            "상점 호출이 중단되었습니다", "Merchant call interrupted"),
    /**
     * MerchantGateway는 현재 "MERCHANT_HTTP_" + status 형태의 동적 코드를 만든다.
     * {@link #fromCode(String)}가 이를 이 값으로 매핑한다. 5xx만 재시도 가능하게 하려면
     * 상태코드를 payload로 분리한 뒤 판단한다. 그 전까지는 보수적으로 false.
     */
    MERCHANT_HTTP_ERROR(Category.FAILED, null, false,
            "상점이 오류를 반환했습니다", "Merchant returned an error"),
    MERCHANT_RESPONSE_INVALID(Category.FAILED, null, false,
            "상점 응답 형식이 올바르지 않습니다", "Invalid merchant response"),
    MERCHANT_INTEGRATION_FAILED(Category.FAILED, null, false,
            "상점 연동 중 알 수 없는 오류가 발생했습니다", "Merchant integration failed");

    public enum Category { HTTP, REJECTED, FAILED }

    private static final String MERCHANT_HTTP_PREFIX = "MERCHANT_HTTP_";
    private static final String PROVIDER_HTTP_PREFIX = "PROVIDER_HTTP_";

    private final Category category;
    private final HttpStatus httpStatus;
    private final boolean retryable;
    private final String messageKo;
    private final String messageEn;

    ErrorCode(Category category, HttpStatus httpStatus, boolean retryable, String messageKo, String messageEn) {
        if ((category == Category.HTTP) != (httpStatus != null)) {
            throw new IllegalStateException("HTTP category requires an HTTP status, others must not have one");
        }
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.messageKo = messageKo;
        this.messageEn = messageEn;
    }

    public Category category() { return category; }

    /** HTTP 카테고리가 아니면 null. */
    public HttpStatus httpStatus() { return httpStatus; }

    public boolean retryable() { return retryable; }

    public String messageKo() { return messageKo; }

    public String messageEn() { return messageEn; }

    public boolean isHttp() { return category == Category.HTTP; }

    /**
     * 실행 레코드에 문자열로 저장된 reasonCode를 enum으로 찾는다 (증거·UI에서 한/영 메시지를 붙일 때 사용).
     * 알 수 없는 코드는 빈 값을 반환한다. 저장된 원본 문자열은 증거이므로 바꾸지 않는다.
     */
    public static Optional<ErrorCode> fromCode(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        if (raw.startsWith(MERCHANT_HTTP_PREFIX)) return Optional.of(MERCHANT_HTTP_ERROR);
        if (raw.startsWith(PROVIDER_HTTP_PREFIX)) return Optional.of(PROVIDER_HTTP_ERROR);
        try {
            return Optional.of(valueOf(raw));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
