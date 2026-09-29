package com.floww.server.common.error;

/**
 * 공통 HTTP 에러 응답 본문 — Issue #13.
 *
 * <p>SA 7장: 오류 응답에는 기계 판독 reasonCode, 사용자용 한·영 설명, taskId/attemptId, 재시도 가능 여부를 둔다.
 *
 * <pre>
 * {
 *   "reasonCode": "EXECUTION_NOT_FOUND",
 *   "code": "EXECUTION_NOT_FOUND",
 *   "message": { "ko": "실행을 찾을 수 없습니다", "en": "Execution not found" },
 *   "taskId": "3f1c...-uuid",
 *   "attemptId": null,
 *   "retryable": false
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code code}: 기존 {@code {"code": "..."}} 형식과의 하위 호환용 별칭. 값은 reasonCode와 같다.
 *       openapi.json의 LegacyError, scripts/smoke.py, 프론트 연동이 이 필드를 읽는다.
 *       소비자가 모두 reasonCode로 옮긴 뒤 제거한다.</li>
 *   <li>{@code taskId}: SA 용어를 따른다. 현재 서버에서는 실행 ID(executionId)가 들어간다.
 *       필드명은 프론트와 확정 필요.</li>
 *   <li>{@code attemptId}: SA 7장 PurchaseAttempt 식별자. 아직 시도 단위가 없어 현재는 항상 null.</li>
 *   <li>null 필드도 생략하지 않는다. 클라이언트가 항상 같은 모양을 받도록 한다.</li>
 *   <li>예외 메시지·스택트레이스·요청 원문은 넣지 않는다(SA 6장: 비밀정보·불필요한 내부 정보 비노출).</li>
 * </ul>
 */
public record ErrorResponse(
        String reasonCode,
        String code,
        Message message,
        String taskId,
        String attemptId,
        boolean retryable) {

    /** 사용자용 한·영 설명. */
    public record Message(String ko, String en) { }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, null, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String taskId, String attemptId) {
        if (errorCode == null) throw new IllegalArgumentException("errorCode is required");
        return new ErrorResponse(
                errorCode.name(),
                errorCode.name(),
                new Message(errorCode.messageKo(), errorCode.messageEn()),
                taskId,
                attemptId,
                errorCode.retryable());
    }
}
