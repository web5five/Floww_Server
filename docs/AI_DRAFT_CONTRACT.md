# F008 AI draft clarification contract / AI 초안 확인 계약

**Status / 상태:** candidate `ai-draft.v1` component contract; proposal only. Product, frontend, core backend, auth and payment owners have not accepted an integration API. This module has no HTTP endpoint or durable task state. / 제안 단계의 컴포넌트 계약이며 연동 API는 담당자 합의 전입니다. HTTP 엔드포인트와 영속 Task 상태가 없습니다.

## Input and output / 입력과 출력

An application passes a bounded `AiDraftAdapter.Turn` list (one to twelve turns, user or assistant role only, final turn user, 4,000 characters per turn and 16,000 total). It cannot pass a system role, tools or an approved mandate. This JSON is an illustrative application-side request, **not** a deployed route:

```json
{"conversation":[{"role":"user","content":"Please get the specified item delivered. I can pay up to 60 USD, but I have not specified the date or fees."}]}
```

The model may call only `propose_ai_draft`. Its arguments use [the candidate schema](ai-draft-schema-v1.json). It must put `null` for unresolved fields. Example proposal:

```json
{"schemaVersion":"ai-draft.v1","objective":"Get the specified item delivered","itemScope":null,"providerCriteria":null,"maximumTotalCost":{"amount":"60","asset":"USD","includesAllUserPaidFees":null},"deadline":null,"fulfillmentCriterion":null}
```

Deterministic preflight returns this kind of result (the issue order follows field order):

```json
{"status":"NEEDS_CLARIFICATION","schemaVersion":"ai-draft.v1","issues":[{"code":"ITEM_SCOPE_MISSING","field":"itemScope","question":"Which exact item or service is in scope?"},{"code":"PROVIDER_CRITERIA_MISSING","field":"providerCriteria","question":"What makes a provider eligible?"},{"code":"FULFILLMENT_CRITERION_MISSING","field":"fulfillmentCriterion","question":"What observable result will count as fulfilled?"},{"code":"COST_FEES_UNRESOLVED","field":"maximumTotalCost.includesAllUserPaidFees","question":"Does this maximum include every charge and fee you pay?"},{"code":"DEADLINE_MISSING","field":"deadline","question":"What is the exact deadline with date, time and timezone?"}],"draft":{"schemaVersion":"ai-draft.v1","objective":"Get the specified item delivered","itemScope":null,"providerCriteria":null,"maximumTotalCost":{"amount":"60","asset":"USD","includesAllUserPaidFees":null},"deadline":null,"fulfillmentCriterion":null}}
```

For a complete proposal, the `status` becomes `READY_FOR_REVIEW`, `issues` is empty, and `draft` remains descriptive. **READY_FOR_REVIEW means structurally complete model proposal. It is not user approval, semantic verification, legal eligibility or executable authority.** A human must review the summary, then the core backend must separately create and enforce any authorized mandate. The AI draft cannot mutate an existing approved mandate. / `READY_FOR_REVIEW`는 구조적으로 채워진 모델 제안일 뿐 사용자 승인, 의미 검증, 법적 적격성 또는 실행 권한이 아닙니다. 사람의 확인과 별도 백엔드 승인 경계가 필요합니다.

## Fields and stable codes / 필드와 코드

| Domain / 영역 | Required proposal content / 필수 내용 | Codes for unresolved values / 미해결 코드 |
| --- | --- | --- |
| Objective / 목표 | Concrete desired outcome | `OBJECTIVE_MISSING`, `OBJECTIVE_AMBIGUOUS` |
| Item or service / 범위 | Exact item or service scope | `ITEM_SCOPE_MISSING`, `ITEM_SCOPE_AMBIGUOUS` |
| Provider / 제공자 | Eligible provider criteria | `PROVIDER_CRITERIA_MISSING`, `PROVIDER_CRITERIA_AMBIGUOUS` |
| Maximum total / 최대 총액 | Positive exact decimal string, asset label, all user-paid fees included | `COST_MISSING`, `COST_AMOUNT_MISSING`, `COST_ASSET_MISSING`, `COST_FEES_UNRESOLVED`, `COST_FEES_EXCLUDED` |
| Deadline / 마감 | Future absolute ISO 8601 date-time with timezone offset | `DEADLINE_MISSING`, `DEADLINE_ABSOLUTE_REQUIRED` |
| Fulfillment / 완료 기준 | Observable result | `FULFILLMENT_CRITERION_MISSING`, `FULFILLMENT_CRITERION_AMBIGUOUS` |

Malformed or unauthorized fields yield `INVALID_PROPOSAL`. Stable invalid codes include `UNKNOWN_FIELD`, `SCHEMA_VERSION_INVALID`, `*_INVALID`, `INSTRUCTION_SHAPED_CONTENT`, `DEADLINE_EXPIRED` and `PROPOSAL_OBJECT_REQUIRED`. Top-level and cost-object extra fields, including `approved`, `recipient`, `signer`, or `active`, are rejected. Known instruction-shaped text inside a descriptive field is rejected; this pattern check is not a semantic or comprehensive prompt-injection detector. Numbers are not accepted for amount; exponent notation, zero and negative amounts are invalid. False or missing fee inclusion needs clarification. A relative date such as “today” needs an absolute time supplied by trusted context; a past or impossible absolute date is invalid. / 알 수 없는 승인 필드, 숫자 금액, 수수료 미포함, 상대 날짜를 임의로 보정하지 않습니다.

Frontend consumers can render `issues[].question` for `NEEDS_CLARIFICATION` and keep `issues[].code` as stable localization/analytics keys. Backend consumers must revalidate the candidate and obtain a separate user confirmation and authorization before any execution. Neither consumer should infer approval from `READY_FOR_REVIEW` or from a model sentence. / 프런트엔드는 코드로 질문을 표시하고, 백엔드는 별도 재검증 및 사용자 확인을 수행해야 합니다.

`AiDraftAdapter.Outcome` contains either a preflight result or a `failureCode`, plus provider provenance when available. Provider or malformed-output failures never return a successful preflight. The provenance includes the reported model ID, provider classification (`local_model_fixture`, `kiln`, or `unknown_model_provider`), tool call ID, generation ID when supplied, usage status/totals when reported, cost when reported, and attempt count. A fixture is not live Kiln evidence. No transcript or private prompt is persisted here.

## Integration owners / 연동 담당자

Frontend and core backend owners still need to agree on transport, locale, conversation/session ownership, trusted context for date resolution, user confirmation UX, schema acceptance, legal/provider checks, and how a reviewed proposal becomes a new mandate. Auth/Magic, wallet, merchant, payment, reservation, task/job and final demo choices remain outside F008. / 전송 방식, 날짜 신뢰 문맥, 사용자 확인, 권한 전환과 적격성 검사는 담당자 합의가 남아 있습니다.

Run local fixture evaluation with `scripts/evaluate_ai_draft.sh`; it reads `eval/ai-draft-scenarios-v1.json` and writes `target/f008-evaluation.json`. It makes only loopback fixture calls.
