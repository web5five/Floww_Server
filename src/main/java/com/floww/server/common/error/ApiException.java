package com.floww.server.common.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * HTTP 에러로 응답할 애플리케이션 예외 — Issue #13.
 * 기존 {@code com.floww.server.ApiException(HttpStatus, String)}을 대체한다.
 *
 * <p>SA 7장 규칙
 * <ul>
 *   <li>HTTP 상태와 재시도 여부는 호출 지점에서 정하지 않고 {@link ErrorCode}에서만 정한다.
 *       그래서 생성자는 HttpStatus를 받지 않는다.</li>
 *   <li>taskId/attemptId를 알 수 있으면 함께 넘긴다. 클라이언트가 어느 작업의 오류인지 연결할 수 있다.</li>
 * </ul>
 *
 * <p>HTTP 카테고리 코드만 받는다. REJECTED/FAILED 코드는 HTTP 오류가 아니라 실행 결과로 저장되는 값이므로
 * 이 예외로 던지지 않는다 (SA 7장: BLOCKED는 시도 상태이고 COMPLETED는 작업 상태다).
 *
 * <p>예외 메시지에는 코드 이름만 넣는다. 사용자 입력이나 비밀정보가 로그로 새지 않게 한다.
 */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String taskId;
    private final String attemptId;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, (String) null, null);
    }

    public ApiException(ErrorCode errorCode, UUID taskId) {
        this(errorCode, taskId == null ? null : taskId.toString(), null);
    }

    public ApiException(ErrorCode errorCode, String taskId, String attemptId) {
        super(requireHttp(errorCode).name());
        this.errorCode = errorCode;
        this.taskId = taskId;
        this.attemptId = attemptId;
    }

    private static ErrorCode requireHttp(ErrorCode errorCode) {
        if (errorCode == null) throw new IllegalArgumentException("errorCode is required");
        if (!errorCode.isHttp()) {
            throw new IllegalArgumentException(errorCode + " is a " + errorCode.category()
                    + " execution outcome, not an HTTP error");
        }
        return errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }

    public HttpStatus status() { return errorCode.httpStatus(); }

    public String taskId() { return taskId; }

    public String attemptId() { return attemptId; }

    public ErrorResponse toResponse() {
        return ErrorResponse.of(errorCode, taskId, attemptId);
    }
}
