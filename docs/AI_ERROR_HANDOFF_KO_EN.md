# F012 AI and error handoff for issue #13 / AI·오류 인계

Status / 상태: integration inventory for Ria's error work, **not** the final common error envelope or an HTTP implementation. Code-derived from the local F012 base `339ce64` and F012 candidate class. / Ria의 오류 작업을 위한 코드 기반 목록이며 최종 공통 오류 형식이나 HTTP 구현이 아닙니다.

## Existing surfaces / 현재 경로

| Surface / 경로 | Current behavior / 현재 동작 | Retry and correlation / 재시도·연관 |
| --- | --- | --- |
| `POST /api/ai/drafts` | `ai-draft-http.v1` envelope, `status=READY_FOR_REVIEW` or `NEEDS_CLARIFICATION` at HTTP 200, or `ERROR` with `error.code`; `evidence` optional. / 전용 봉투와 선택적 증거. | Stateless proposal; no request idempotency. A caller retry can trigger a second billable model call. No `taskId` exists before task creation. / 작업 생성 전 taskId 미확정. |
| Legacy `POST /api/executions` and `/{id}/run` | App exceptions use `{"code":"..."}`; run returns execution object with `CREATED`, `RUNNING`, `REVIEWED`, `REJECTED` or `FAILED`. `REVIEWED` is a local quote precheck only. / 기존 실행 객체 상태. | Create requires `Idempotency-Key`; a repeated run claim returns 409. This is not payment idempotency. / 생성 키와 실행 클레임은 지급 멱등성 아님. |
| F012 Java component | `PROPOSED`, `CLARIFICATION_REQUIRED`, `NO_CANDIDATE`, `REJECTED`, `MODEL_FAILURE`, per-quote reasons and optional provenance. / 내부 결과. | No HTTP route, durable task, request key or payment. Caller retry can call Kiln again. / HTTP·저장·결제 없음. |

Mapped AI draft HTTP errors: 400 `INVALID_REQUEST` or `INVALID_CONVERSATION`; 401 `UNAUTHORIZED`; 413 `REQUEST_TOO_LARGE`; 415 `UNSUPPORTED_MEDIA_TYPE`; 502 `MODEL_PROPOSAL_FAILED` or `MODEL_PROPOSAL_INVALID`; 503 `PROVIDER_NOT_CONFIGURED`; 504 `PROVIDER_TIMEOUT`. Mapped responses use `Cache-Control: no-store`. Framework errors such as malformed UUID or unexpected server failures are not guaranteed to use either application envelope. / 프레임워크 오류에는 동일 봉투를 보장하지 않습니다.

Current KilnClient internal failures include `KILN_NOT_CONFIGURED`, `RUN_TIME_LIMIT`, `PROVIDER_HTTP_<status>`, `PROVIDER_ACCESS_BLOCK_1010`, `PROVIDER_UNAVAILABLE`, `PROVIDER_INTERRUPTED`, `PROVIDER_REQUEST_INVALID`, `MODEL_OUTPUT_INVALID`, and `MODEL_OUTPUT_TRUNCATED`. F012 also distinguishes `MODEL_MISMATCH`, `MODEL_TOOL_INVALID`, `MODEL_ARGUMENTS_INVALID`, `MODEL_QUOTE_NOT_ELIGIBLE`, and post-call `EXPIRED_DURING_PROPOSAL`. Clarification, no-candidate and policy rejection are business outcomes, not provider transport errors. / 정보 부족·후보 없음·정책 거부는 모델 통신 실패와 분리합니다.

KilnClient allows one internal retry (two attempts total) for HTTP 429/502/503/504 with bounded `Retry-After`/delay, and for I/O failure when time remains. 401/402/403 and malformed output are not retried internally. A provider response may carry real usage, cost and generation ID; missing usage stays `unknown`, never estimated. Endpoint-derived `modelEvidenceMode` is `kiln` only for the exact official endpoint, `local_model_fixture` for loopback, or `unknown_model_provider` elsewhere. / 제공자 내부 재시도와 API 호출자의 새 요청은 서로 다릅니다. 사용량이 없으면 추정하지 않습니다.

## Proposed issue #13 decisions / #13 미결정 사항

Ria/core owners should define the common envelope, task correlation lifecycle, localization/UI treatment, and retry guidance only after deciding when a durable task ID is created. Before creation, use a request correlation value if needed; do not fabricate `taskId`. Preserve provider attempt count and evidence without leaking credentials. Treat payment timeout or unknown payment status as **unknown** and reconcile before any payment retry; no blanket `retryable=true`. Existing F012 codes are candidate internal reason codes and must not be hardcoded as the final public schema. / taskId가 없으면 만들어내지 않습니다. 지급 상태 미확인은 조회·대조 후 처리하며 일괄 재시도를 허용하지 않습니다.
