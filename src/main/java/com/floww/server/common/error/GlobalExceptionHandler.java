package com.floww.server.common.error;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 공통 예외 → {@link ErrorResponse} 변환 — Issue #13. 기존 {@code com.floww.server.ApiErrors}를 대체한다.
 *
 * <p>SA 7장: 오류 응답은 reasonCode, 한·영 설명, taskId/attemptId, 재시도 가능 여부를 담는다.
 * 앱이 던진 예외뿐 아니라 프레임워크 오류(잘못된 UUID·파라미터, 깨진 JSON, 없는 경로, 메서드 불일치,
 * 예기치 않은 서버 오류)도 같은 형식으로 응답해, 클라이언트가 한 가지 모양만 처리하게 한다.
 *
 * <p>범위 밖
 * <ul>
 *   <li>/api/ai/drafts는 컨트롤러 안에서 자체 envelope(ai-draft-http.v1)로 응답한다(F010 계약).
 *       그 경로에서 처리되지 않은 예외만 여기로 온다.</li>
 *   <li>인증 실패는 필터(DevAuthFilter)에서 응답하므로 이 advice를 거치지 않는다.
 *       필터에서도 {@link ErrorResponse}를 직접 쓴다.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 애플리케이션이 던진 HTTP 오류. 상태·재시도 여부는 ErrorCode에서 결정된다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(e.toResponse());
    }

    /** 본문 없음, 깨진 JSON, 알 수 없는 필드(fail-on-unknown-properties=true). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.MALFORMED_JSON);
    }

    /**
     * 잘못된 UUID 경로 변수, 숫자가 아닌 limit/after 등 타입 변환 실패,
     * 필수 파라미터·헤더 누락(ServletRequestBindingException 하위 타입).
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception e) {
        return respond(ErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoRoute(NoResourceFoundException e) {
        return respond(ErrorCode.ROUTE_NOT_FOUND);
    }

    /** 405에는 RFC 9110에 따라 Allow 헤더를 붙인다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.httpStatus());
        if (allowed != null && !allowed.isEmpty()) {
            builder.header(HttpHeaders.ALLOW, allowed.stream().map(HttpMethod::name)
                    .sorted().reduce((a, b) -> a + ", " + b).orElse(""));
        }
        return builder.body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 그 외 모든 예외. 원인은 서버 로그에만 남기고 응답에는 넣지 않는다(SA 6장: 비밀정보 비노출).
     * 로그에도 키·토큰 값이 들어가지 않도록, 예외 메시지에 비밀정보를 넣는 코드를 만들지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 위에서 따로 다루지 않은 Spring MVC 4xx 예외(org.springframework.web.ErrorResponse 구현체)는
        // 클라이언트 오류이므로 500으로 바꾸지 않는다.
        if (e instanceof org.springframework.web.ErrorResponse spring
                && spring.getStatusCode().is4xxClientError()) {
            return respond(spring.getStatusCode().value() == 404
                    ? ErrorCode.ROUTE_NOT_FOUND : ErrorCode.INVALID_INPUT);
        }
        log.error("Unhandled exception mapped to INTERNAL_ERROR", e);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
    }
}
