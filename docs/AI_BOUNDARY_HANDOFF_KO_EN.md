# F026 AI proposal boundary / AI 제안 경계 인계

Status: locally tested internal Java seam for issue #18 (partial). No public API, DB schema, signer, merchant service, or payment flow is added. / 상태: 이슈 #18의 일부인 내부 Java 경계를 로컬 검증했습니다. 공용 API·DB·서명·판매자 서비스·지급 흐름은 추가하지 않았습니다.

## Callable entries / 호출 지점

- `com.floww.server.aiproposal.ExactBaseUnits.positive(baseUnits)` and `nonNegativeFee(baseUnits)` are public Java helpers for a trusted producer in another backend package; they return `BigInteger` or `null` for invalid input. `displayToPositiveBaseUnits(display, trustedDecimals)` is an **explicit** display conversion returning a canonical string or `null`; do not apply it to a base-unit string. They add no HTTP API. / 다른 백엔드 패키지에서도 호출 가능한 Java 유틸리티이며, 기본 단위 문자열에는 표시 금액 변환을 재적용하지 않습니다.
- `new AiMerchantProposal(kiln, clock).propose(context, quotes)` remains callable and retains its `ACTIVE` guard. / 기존 호출과 `ACTIVE` 검사를 유지합니다.
- `new AiProposalBoundary(proposal, clock).propose(initialSnapshot, currentSnapshotReader)` adds a server-supplied snapshot and a fresh owner-scoped read **after** a valid model selection. `CurrentSnapshotReader.read(ownerKey, taskRef, mandateRef)` is implemented by the future trusted core/merchant producer, not by an HTTP client. / 최신 조회는 신뢰 가능한 서버 생산자가 구현하며 클라이언트 입력을 믿지 않습니다.

`Snapshot` holds `ownerKey`, `taskVersion`, `quoteSetVersion`, existing `Context`, existing `Quote` list, and `quoteVersions` by exact quote ID. The list and map are copied on construction. The caller must derive `ownerKey` from authenticated server context, resolve that owner's task and mandate, pin each server-fetched quote version, and make the reader query the same owner/task/mandate scope. It must ensure the data is authoritative and internally consistent before the call. A missing, changed, removed, revoked, or expired snapshot cannot produce `PROPOSED`. / 소유자 키는 서버 인증 문맥에서만 유도하고, 견적과 버전은 신뢰 원천에서 조회해야 합니다. 누락·변경·삭제·철회·만료 시 `PROPOSED`를 내지 않습니다.

Example (synthetic six-decimal asset, no real medical or chain assertion) / 예시(6자리 합성 자산):

```text
display budget "25" -> trusted-decimals conversion -> "25000000" base units
pharmacy A: quoteId=q-a, merchantId=pharmacy-a, recipient=recipient-a, totalBaseUnits="23500000" (23.5)
pharmacy B: quoteId=q-b, merchantId=pharmacy-b, recipient=recipient-b, totalBaseUnits="24000000" (24)
pharmacy C: quoteId=q-c, merchantId=pharmacy-c, recipient=recipient-c, totalBaseUnits="26000000" (26; OVER_BUDGET)
model tool output: {"quoteId":"q-a"}
AI result: status=PROPOSED, proposedQuote=<unchanged q-a snapshot>, provenance=<actual model usage>
```

The selected `quoteId` binds the exact merchant, recipient, asset and amount in the returned quote. A core owner can derive `merchantId` from that selected authoritative quote when building a later internal DTO/lookup; replacing the selection with merchant-only pricing would lose quote binding. The future sink, if approved by its owner, persists the result and actual provenance. This component invents no table or public field. / 선택한 `quoteId`로 원본 견적의 판매자·수취인·자산·금액을 확정하며, 후속 내부 DTO에서 그 견적의 `merchantId`를 유도할 수 있습니다. 저장 위치는 담당자가 제공해야 합니다.

## Exact amounts and outcomes / 정확한 금액과 결과

Budget and quote total require canonical ASCII decimal integer base-unit strings from `1` through `2^256-1`; zero, signs, whitespace, exponent, decimal point, non-ASCII numerals, leading zeros, oversized input, and overflow fail before inference. The separate fee parser permits canonical `0`. Display conversion requires an explicit trusted decimals argument (0..255), rejects excess fractional precision without rounding, and returns a canonical base-unit string. Java `BigInteger` preserves values above JavaScript's safe integer. / 예산·견적은 양의 ASCII 정수 기본 단위이고 uint256을 넘지 않습니다. 수수료 연산에서만 `0`을 허용합니다. 소수 표시 금액은 신뢰된 자릿수를 명시할 때만 정확히 변환하고 반올림하지 않습니다.

`SNAPSHOT_BOUNDARY_MISSING` maps to local `CLARIFICATION_REQUIRED` before a model call. `SNAPSHOT_STALE`, `SNAPSHOT_UNAVAILABLE`, `SNAPSHOT_READ_FAILED`, and `SNAPSHOT_EXPIRED` map to local `REJECTED` with no selected quote; after a model call its real provenance is retained. Existing `NO_CANDIDATE`, `ELIGIBILITY_EVIDENCE_MISSING`, `MODEL_*`, and provider failures keep the original component behavior. These are **recommendations for future shared error mapping**, not additions to `ErrorResponse` or `TaskStatus`; unknown provider/payment state must not be presented as approved or complete. / 위 사유는 내부 제안 결과이며 공통 오류·Task 상태 변경이 아닙니다. 모델 사용 증거는 거절 시에도 보존합니다.

The current equality check rejects *any* change in the pinned snapshot, including the same quote ID with a changed amount or recipient. This is an optimistic freshness check, not a transactional lock. Core/signing owners Ria and Taeheon must independently verify authorization, delegation/signature, current quote, budget, fees, expiry, idempotency and payment state, then enforce them atomically at execution. `PROPOSED` never means `ALLOW`, `paymentReady`, wallet authorization, purchase or fulfillment. No preapproval Task flow is created; the existing `ACTIVE` guard is unchanged. / 스냅샷 비교는 거래 잠금이나 서명 검증이 아닙니다. 실행 담당자가 권한과 최신 상태를 독립적으로 확인하고 실행 경계에서 원자적으로 재검사해야 합니다.

Completed: exact amount parsing, local proposal adapter, stale/read-failure rejection, synthetic regression tests. Blocked on owner decisions: final Task/mandate DTO, producer/reader and persistence wiring, real merchant snapshots, auth and signing enforcement, and combined integration validation. / 완료: 금액·최신성 경계와 합성 테스트. 미결정: 공통 DTO, 신뢰 생산자·조회·저장 연결, 실제 판매자·인증·서명 및 통합 검증.
