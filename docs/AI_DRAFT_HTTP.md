# F010 candidate AI draft HTTP contract / AI 초안 HTTP 후보 계약

**Status / 상태:** `ai-draft-http.v1` is an authenticated development transport for the F008 proposal component. It has not been accepted as the production frontend/core/Magic contract. It creates no stored review snapshot, confirmation receipt, mandate, task, reservation, execution, payment, wallet login or fulfillment. / F008 제안 기능을 호출하는 개발용 전송 API입니다. 제품 인증·실행 계약으로 승인된 것은 아니며 저장된 확인 기록이나 실행 권한을 만들지 않습니다.

## Request / 요청

`POST /api/ai/drafts` requires the existing server-side development bearer identity. The server ignores no client identity fields: it rejects every field except `conversation`. Only `Content-Type: application/json` with no charset or UTF-8 charset is accepted. The body must be well-formed UTF-8 JSON, at most **131,072 bytes (128 KiB)**, including a chunked body without `Content-Length`; byte 131,073 triggers `413` before parsing or a model call. Duplicate JSON keys, trailing JSON, unknown fields and wrong types are rejected before a model call. / 기존 개발용 Bearer 인증이 필요합니다. 클라이언트의 소유자·승인 필드는 허용하지 않습니다. UTF-8 JSON 본문은 청크 전송을 포함해 최대 128 KiB입니다.

```json
{"conversation":[{"role":"user","content":"Please get one specified paperback delivered. My maximum is 60 USD including every charge and fee. Use an authorized bookstore and finish by 2099-01-01T00:00:00Z."}]}
```

`conversation` has 1–12 turns. Each turn has exactly `role` and `content`; role is `user` or `assistant`, the final turn is `user`, content is nonblank and at most 4,000 Java characters per turn, with at most 16,000 across turns. The [F008 contract](AI_DRAFT_CONTRACT.md) defines the model draft and structural checks. / 대화는 1–12개 발화이며 마지막 발화는 사용자입니다. 발화별 4,000자, 전체 16,000자 제한을 적용합니다.

## Response / 응답

Every mapped response uses `Cache-Control: no-store`. The stable envelope is distinct from `ai-draft.v1` (the draft schema):

```json
{"httpContractVersion":"ai-draft-http.v1","status":"READY_FOR_REVIEW","draft":{"schemaVersion":"ai-draft.v1","objective":"Get one paperback delivered","itemScope":"One specified paperback","providerCriteria":"Authorized bookstore","maximumTotalCost":{"amount":"60","asset":"USD","includesAllUserPaidFees":true},"deadline":"2099-01-01T00:00:00Z","fulfillmentCriterion":"Delivery recorded at the specified address"},"issues":[],"evidence":{"modelId":"qwen3-32b","modelEvidenceMode":"local_model_fixture","toolCallId":"fixture-call","generationId":"fixture-generation","usageStatus":"reported","usage":{"prompt_tokens":8,"completion_tokens":6,"total_tokens":14},"cost":null,"attempts":1},"error":null}
```

This JSON is an illustrative **local fixture** response, not a live Kiln call or an approval. `NEEDS_CLARIFICATION` also returns HTTP 200 with a partial `draft` and F008 `issues[]` questions. `READY_FOR_REVIEW` means structurally complete model text, not that its claims are true or that anyone has approved it. `evidence` is separate from draft terms; IDs, usage and cost are reported only when present and validated, and `usageStatus: "unknown"` or null values must remain unknown. `modelEvidenceMode` is `kiln` only for the exact official endpoint, `local_model_fixture` for loopback, or `unknown_model_provider` otherwise. / 예시는 로컬 테스트 응답입니다. `READY_FOR_REVIEW`는 구조 검사 결과일 뿐 사용자 승인·결제 허가가 아닙니다. 사용량이나 비용이 없는 경우 추정하지 않습니다.

Errors use the same envelope with `status: "ERROR"`, `draft: null`, `issues: []`, an `error.code`, and `evidence` when a model attempt produced safe provenance. No raw request, prompt, provider error body, bearer token or API key appears in an error:

```json
{"httpContractVersion":"ai-draft-http.v1","status":"ERROR","draft":null,"issues":[],"evidence":null,"error":{"code":"INVALID_REQUEST"}}
```

| HTTP | Stable code | Meaning / 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Malformed UTF-8/JSON, duplicate/trailing JSON, wrong shape/type or unknown fields / 형식 오류 |
| 400 | `INVALID_CONVERSATION` | F008 turn/count/content limits / 대화 제한 위반 |
| 401 | `UNAUTHORIZED` | Missing or wrong development bearer token / 인증 실패 |
| 413 | `REQUEST_TOO_LARGE` | Body exceeds 128 KiB / 본문 초과 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content type is not concrete `application/json` with UTF-8 / 미지원 형식 |
| 503 | `PROVIDER_NOT_CONFIGURED` | No server-side Kiln key configured; attempts = 0 / 제공자 설정 없음 |
| 502 | `MODEL_PROPOSAL_FAILED` | Provider failure, invalid model/tool/arguments / 제공자·모델 오류 |
| 502 | `MODEL_PROPOSAL_INVALID` | F008 structural preflight rejected model terms / 모델 초안 구조 거부 |
| 504 | `PROVIDER_TIMEOUT` | KilnClient's 45-second request deadline was reached / 제공자 시간 제한 |

KilnClient may make up to two attempts for transient provider errors. **Retrying this HTTP request can create another billed proposal call**, including after a client-side timeout. This route promises no request idempotency, execution idempotency or payment behavior. / 이 HTTP 요청을 재시도하면 과금되는 모델 호출이 새로 발생할 수 있습니다. 실행·결제 멱등성을 제공하지 않습니다.

## Local curl / 로컬 호출 예시

Run these only against a locally configured server. Keep the development bearer token and Kiln API key on the server or in a trusted local shell; never put them in browser source or a `NEXT_PUBLIC_` variable. / 개발용 토큰과 Kiln 키는 서버 측에 보관하고 브라우저 코드에 넣지 않습니다.

```sh
curl --fail-with-body -sS 'http://127.0.0.1:8080/api/ai/drafts' \
  -H 'Authorization: Bearer <SERVER_SIDE_DEV_TOKEN>' \
  -H 'Content-Type: application/json' \
  --data-binary '{"conversation":[{"role":"user","content":"Please draft a purchase scope for one paperback; I have not specified the budget or deadline."}]}'
```

After the UI shows `issues[].question`, send the prior user turn, the assistant clarification and a new user answer as a fresh bounded conversation. The frontend should show the questions or descriptive draft as primary content and put `evidence` behind a details view; keep `issues[].code` for stable localization. It must show exact terms to the user and integrate owner-authenticated durable F009 confirmation separately. Never send this draft to the legacy `confirmed=true` path. / 프런트엔드는 질문 또는 초안을 먼저 보여주고 모델 증거는 상세 보기로 분리합니다. 사용자가 본 정확한 조건의 별도 확인·기록이 필요하며 기존 `confirmed=true` 경로로 연결하지 않습니다.

Core/Magic authentication, durable conversation/task ownership, date context, semantic/provider/legal checks, F009 receipt persistence and authorization, frontend UX, wallet, merchant, payment and fulfillment remain owner integration gaps. Local tests use fixtures; a separately recorded controller live call is documented in [F010 evidence](evidence/f010/README.md). / 실제 제품 인증, 영속 기록, 적격성 판단, 지갑·결제·이행은 담당자 연동 과제입니다.
