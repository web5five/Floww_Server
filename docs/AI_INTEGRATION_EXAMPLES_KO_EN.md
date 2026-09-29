# F013 executable AI integration examples / 실행 가능한 AI 연동 예시

**Status / 상태:** internal component handoff for Ria; final shared contract pending. The checked-in JSON is a **test fixture transport**, not a network request, OpenAPI schema, team-approved API, signing request, or payment record. / Ria의 최종 공통 계약을 기다리는 내부 컴포넌트 인계입니다. JSON은 **테스트용 전달 형식**이며 네트워크 API·서명 요청·결제 기록이 아닙니다.

The actual entrypoint is `new AiMerchantProposal(kilnClient, clock).propose(context, quotes)` in `src/main/java/com/floww/server/aiproposal/AiMerchantProposal.java`. The types are the `MerchantProposal.Context`, `Quote`, `Result`, `Finding`, `Asset`, `Pair`, and `Provenance` records. There is no F012 controller or Spring registration. / 실제 호출은 위 Java 메서드이며 F012에는 컨트롤러나 Spring 등록이 없습니다.

## Complete representative input / 대표 입력 전체

This is the `input` object in [`merchant-proposal-examples.json`](../src/test/resources/aiproposal/merchant-proposal-examples.json), at fixed test clock `2040-01-01T00:00:00Z`. The three quotes include total prices of 43, 47, and 63 synthetic MockUSDC. Decimal precision 6 is a fixture choice only. `maximumTotalBaseUnits: "60000000"` means 60 fixture tokens; no real chain, token address, recipient, prescription, merchant or legal status is represented. / 시계·체인·토큰·수취인·처방 적격성은 전부 합성 데이터입니다.

```json
{
  "context": {
    "taskRef": "synthetic-task-1",
    "mandateRef": "synthetic-mandate-1",
    "mandateRevision": "synthetic-revision-1",
    "itemId": "synthetic-item",
    "maximumTotalBaseUnits": "60000000",
    "asset": {"chainId": "synthetic-chain", "tokenAddress": "synthetic-MockUSDC", "decimals": 6},
    "deadline": "2040-01-01T01:00:00Z",
    "permittedPairs": [
      {"merchantId": "A", "recipient": "synthetic-recipient-A"},
      {"merchantId": "B", "recipient": "synthetic-recipient-B"},
      {"merchantId": "C", "recipient": "synthetic-recipient-C"}
    ],
    "requiredFulfillmentBy": "2040-01-01T00:50:00Z",
    "prescriptionEligible": true,
    "identityEligible": false,
    "mandateState": "ACTIVE"
  },
  "quotes": [
    {"quoteId": "quote-A43", "merchantId": "A", "recipient": "synthetic-recipient-A", "itemId": "synthetic-item", "asset": {"chainId": "synthetic-chain", "tokenAddress": "synthetic-MockUSDC", "decimals": 6}, "totalBaseUnits": "43000000", "expiresAt": "2040-01-01T00:10:00Z", "inStock": true, "promisedFulfillmentAt": "2040-01-01T00:40:00Z", "prescriptionRequired": true, "identityRequired": false},
    {"quoteId": "quote-B47", "merchantId": "B", "recipient": "synthetic-recipient-B", "itemId": "synthetic-item", "asset": {"chainId": "synthetic-chain", "tokenAddress": "synthetic-MockUSDC", "decimals": 6}, "totalBaseUnits": "47000000", "expiresAt": "2040-01-01T00:10:00Z", "inStock": true, "promisedFulfillmentAt": "2040-01-01T00:40:00Z", "prescriptionRequired": true, "identityRequired": true},
    {"quoteId": "quote-C63", "merchantId": "C", "recipient": "synthetic-recipient-C", "itemId": "synthetic-item", "asset": {"chainId": "synthetic-chain", "tokenAddress": "synthetic-MockUSDC", "decimals": 6}, "totalBaseUnits": "63000000", "expiresAt": "2040-01-01T00:10:00Z", "inStock": true, "promisedFulfillmentAt": "2040-01-01T00:40:00Z", "prescriptionRequired": true, "identityRequired": false}
  ]
}
```

For `A43-only-eligible`, the fixture fake returns `propose_purchase` arguments `{"quoteId":"quote-A43"}`. Expected semantic `MerchantProposal.Result` values (the real record also contains a `Provenance` object): / 기본 사례에서 가짜 모델 응답과 기대 결과는 다음과 같습니다.

```json
{
  "status": "PROPOSED",
  "reason": null,
  "taskRef": "synthetic-task-1",
  "mandateRef": "synthetic-mandate-1",
  "mandateRevision": "synthetic-revision-1",
  "proposedQuote": {"quoteId": "quote-A43", "merchantId": "A", "recipient": "synthetic-recipient-A", "itemId": "synthetic-item", "asset": {"chainId": "synthetic-chain", "tokenAddress": "synthetic-MockUSDC", "decimals": 6}, "totalBaseUnits": "43000000", "expiresAt": "2040-01-01T00:10:00Z", "inStock": true, "promisedFulfillmentAt": "2040-01-01T00:40:00Z", "prescriptionRequired": true, "identityRequired": false},
  "findings": [
    {"quoteId": "quote-A43", "reasons": []},
    {"quoteId": "quote-B47", "reasons": ["IDENTITY_NOT_VERIFIED"]},
    {"quoteId": "quote-C63", "reasons": ["OVER_BUDGET"]}
  ],
  "provenance": {"modelId": "qwen3-32b", "modelEvidenceMode": "local_model_fixture", "finishReason": "tool_calls", "toolCallId": "synthetic-call", "generationId": null, "usageStatus": "unknown", "usage": {}, "cost": null, "attempts": 1}
}
```

This is a semantic rendering of the Java record, not a produced HTTP response. The fixture fake reports no token usage or cost. The test compares these expected fields against the actual record and checks that `proposedQuote` is the same original `Quote` instance; it does not fabricate payment or provider evidence. / 위 JSON은 Java 결과의 의미를 보여 주며 실제 HTTP 응답이 아닙니다.

## Case changes and expected outcomes / 사례별 변경과 결과

The fixture contains one complete input plus 12 independent `cases`. `contextChanges` and `quoteChanges` replace named **top-level fixture fields**; `quoteIds` selects input snapshots. This compact fixture notation exists only inside the test adapter. It does not define a backend patch operation. / 변경 표기는 테스트 자료 중복을 줄이기 위한 것이며 서버 수정 API가 아닙니다.

| Case / 사례 | Expected / 기대 결과 |
| --- | --- |
| A43 only eligible / A만 적격 | `PROPOSED`, exact A43, one fake model call |
| Identity verified / 신원 확인 | B47 becomes eligible; fake may select exact B47, one call |
| Missing maximum / 예산 누락 | `CLARIFICATION_REQUIRED: BOUNDARY_MISSING`, zero calls |
| Only B47 and C63 / 적격 견적 없음 | `NO_CANDIDATE: NO_ELIGIBLE_QUOTE`, identity and budget reasons, zero calls |
| Identity unknown / 신원 정보 미확인 | B47 reason `IDENTITY_EVIDENCE_MISSING`, `CLARIFICATION_REQUIRED`, zero calls; JSON `null` is **unknown**, distinct from `false` / 미확인과 미검증은 다름 |
| Model names C63 / 모델이 C 지정 | `REJECTED: MODEL_QUOTE_NOT_ELIGIBLE`; no A substitution |
| Model names unknown ID / 미등록 ID | same rejection; no substitution |
| Model adds `approved` / 승인 인수 주입 | `MODEL_FAILURE: MODEL_ARGUMENTS_INVALID`; only `quoteId` is accepted |
| Stale, revoked, completed mandate / 만료·철회·완료 | three separate cases, `REJECTED: MANDATE_INACTIVE_OR_EXPIRED`, zero calls |
| A43 expires during inference / 추론 중 견적 만료 | injected clock advances three seconds after the fake call; `REJECTED: EXPIRED_DURING_PROPOSAL` |

The fake `next()` invocation count is a **component boundary count**. In production `KilnClient.next()` can make up to two HTTP attempts internally for configured retryable failures. A caller retry of `propose()` is a fresh operation and may create another billable model call. / 여기의 호출 수는 가짜 경계 호출 수이며 실제 HTTP 시도 횟수나 모델 요금을 뜻하지 않습니다.

## Exact Java mapping worksheet for Ria / Ria용 Java 매핑 표

These are candidate internal mappings awaiting Ria's authoritative shared fields and error/state names. A task at the AI draft stage has **no approved mandate yet**; do not fabricate `mandateRef` or `mandateRevision` to invoke this post-approval component. / AI 초안 단계에는 승인된 위임이 없으므로 위임 참조를 만들어 호출하지 않습니다.

| Actual F012 field / 실제 필드 | Required owner source and check / 담당자 원천과 확인 |
| --- | --- |
| `Context.taskRef`, `itemId` | Core's stable task and approved item identity; map only after task creation / 태스크 생성 후 안정 ID와 승인 품목 |
| `Context.mandateRef`, `mandateRevision`, `mandateState` | Core's approved mandate ID, stable revision and fresh lifecycle state; `ACTIVE` is only a proposal snapshot / 최신 상태 스냅샷일 뿐 지급 권한 아님 |
| `Context.maximumTotalBaseUnits`, `asset` | Approved maximum as positive decimal integer **base-unit string**; exact `Asset(chainId, tokenAddress, decimals)` from the final token/network decision / 최종 네트워크·토큰은 미결정 |
| `Context.deadline`, `requiredFulfillmentBy` | Absolute `Instant` in UTC (`Z` in fixture), strictly future; core chooses actual boundaries / UTC 절대 시각 |
| `Context.permittedPairs` | Approved exact `(merchantId, recipient)` pairs from core; recipient must match signing policy / 판매자와 수취인 결합 |
| `Context.prescriptionEligible`, `identityEligible` | Trusted verified tri-state `Boolean`; `null` unknown, `false` negative, `true` verified. Never trust request booleans / 검증 주체 필요 |
| `Quote.quoteId`, `merchantId`, `recipient`, `itemId` | Core/merchant fresh immutable quote snapshot and exact task/allowlist association / 최신 견적 연계 |
| `Quote.asset`, `totalBaseUnits` | Exact asset and positive integer total including delivery; additional user-paid gas/fees are outside F012 / 배송 포함 금액이며 별도 수수료 정책 필요 |
| `Quote.expiresAt`, `inStock`, `promisedFulfillmentAt`, `prescriptionRequired`, `identityRequired` | Merchant snapshot and verified requirements; core rechecks at execution / 실행 시 재조회 |
| `Result.status`, `reason`, `findings` | Proposal/filter diagnostics to map to Ria's final error/status contract; no state mutation implied / 공통 오류·상태 계약 확정 대기 |
| `Result.taskRef`, `mandateRef`, `mandateRevision`, `proposedQuote` | Echoed references and original quote only on `PROPOSED`; bind to stable current mandate/revision, then run core policy / 별도 정책 통과 필요 |
| `Result.provenance` | Model/evidence metadata, not approval or fulfillment proof; retain truthful endpoint mode, attempts and usage completeness / 모델 증거와 승인 증거 분리 |

**Delegation boundary / 위임 경계:** Section 18 of vision page `12746767` v6 calls for delegation that exists, has not expired, and is tied to the current mandate/version at deterministic policy. `Context.mandateState == ACTIVE` and a matching `mandateRevision` do **not** establish that delegation. Core/signer must resolve current delegation and verify it at the actual enforcement boundary before production execution. F012 adds no delegation field or signing interface. / `ACTIVE`와 revision 일치만으로 위임 권한이 성립하지 않으며, 실행 경계에서 실제 위임 상태와 버전을 검증해야 합니다.

The live merchant quote, task, confirmation receipt, mandate and delegation can change after F012 snapshots. Core/signer must fetch authoritative current state, compare exact quote/recipient/asset/amount, check the earlier applicable deadlines, budget and full user-paid fees, and enforce idempotency/reconciliation before any payment attempt. `PROPOSED` is not approval, signature, broadcast, purchase, delivery or user acceptance. After an already approved mandate, do not invent a new human approval for every purchase within its scope; apply the actual approved scope and signer policy. / 실행 직전 최신 상태·기한·수취인·비용·중복 지급을 확인해야 합니다. 이미 승인된 위임 범위 안의 구매마다 별도 승인을 임의로 추가하지 않습니다.

## Offline reproduction / 오프라인 재현

From this repository root, with the existing Maven cache (no DB, Docker or external/paid provider request; the existing F012 test uses loopback HTTP): / 저장소 루트에서 실행합니다. 기존 F012 테스트는 로컬 루프백 HTTP를 사용합니다.

```sh
JAVA_HOME='/Users/geondongkim/Floww/.local-tools/jdk-21.0.12.1+1/Contents/Home' ./mvnw -o -q -Dtest=MerchantProposalExamplesTest,AiMerchantProposalTest test
```

The checked-in test reads the JSON, constructs the actual F012 Java records, calls the real `AiMerchantProposal`, and uses a local `KilnClient` subclass that never contacts its loopback placeholder URL. It compares fixed expected statuses, reasons, per-quote findings, exact selected quote binding, model boundary count, provenance mode, untouched inputs and the wall-clock deadline translation from the far-future injected clock. It does not assess real model ranking quality or any live execution. / 테스트는 실제 컴포넌트 호출을 검증하지만 실모델 품질이나 결제 성공을 증명하지 않습니다.
