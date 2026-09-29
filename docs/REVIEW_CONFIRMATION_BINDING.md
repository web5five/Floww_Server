# F009 reviewed draft confirmation binding / 검토 초안과 사용자 확인의 일치 검증

**Status / 상태:** implemented candidate Java component for `web5five/Floww_Server#7`; the frontend, core-auth, wallet and payment owners have not accepted a shared HTTP API or spending mandate. No route, durable store, wallet call or payment call is added. / 구현된 후보 Java 컴포넌트입니다. 공용 HTTP API나 지출 위임은 담당자 합의 전이며, 새 라우트·저장소·지갑·지급 호출은 없습니다.

## Java call sequence / Java 호출 순서

1. The backend takes an F008 proposal and trusted server values. Call `ReviewConfirmationBinding.prepare(proposal, new Context(ownerId, taskId, revision, createdAt), clock)`. The revision must be positive; IDs must be nonempty ASCII identifiers (`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`). `createdAt` is the persisted **review creation instant**, never a new request time. Preparation re-runs F008 at trusted `clock` time and accepts only `READY_FOR_REVIEW`, including a deadline strictly after now. / 서버 문맥과 신뢰 시각으로 F008을 재검증하고 검토 생성 시각을 고정합니다.
2. The frontend shows the exact review snapshot terms to the authenticated user. The core-auth adapter, after its own identity and intent checks, persists a confirmation receipt tied to that snapshot's `digest`, owner, task and revision. A model sentence, page field, or public `approved=true` flag is **not** a receipt. The component does not issue receipts. / 화면은 동일한 초안 전체를 보여주고, 인증 담당 경계만 확인 기록을 생성합니다. 모델 문장이나 `approved=true`는 확인 기록이 아닙니다.
3. Before creating any executable mandate, the backend loads the **current** reviewed draft, owner, task, revision and original `createdAt` from trusted storage and fetches the current authoritative confirmation state. Call `verify(snapshot, currentDraft, currentContext, receipt, clock)`. If snapshots are persisted, reload their full draft and original context from trusted storage and call `prepare` to reconstruct the deterministic digest; compare it with the stored digest and receipt digest. Never reconstruct `createdAt` using the current request time. Recheck after amendments, revocation or revision changes. / 실제 위임 직전에 현재 서버 상태와 최신 확인 상태를 다시 읽고 비교합니다. 재로딩 시 원래 검토 생성 시각과 전체 초안을 사용합니다.
4. `CONFIRMATION_MATCHED` means **binding checks passed only**. The domain owner must still perform fresh authentication, current revocation lookup, eligibility, budget/funds checks, any reservation, wallet or signature authorization, and atomic enforcement against cancellation or budget races at its own final persistence/payment boundary. Unknown payment status remains unknown and requires reconciliation. / 일치 결과는 결속 검사 통과만 뜻합니다. 최종 권한·예산·취소 경쟁 조건은 도메인 담당자의 강제 경계에서 처리해야 합니다.

## Input and result example / 입력·결과 예시

The Java component takes the complete F008 `ai-draft.v1` object. This abbreviated display lists all fields and illustrates values, **not an HTTP request**:

```json
{"schemaVersion":"ai-draft.v1","objective":"Obtain one report","itemScope":"One PDF report","providerCriteria":"Able to deliver the PDF","maximumTotalCost":{"amount":"10.0","asset":"USD","includesAllUserPaidFees":true},"deadline":"2026-10-01T12:00:00Z","fulfillmentCriterion":"Requested PDF is delivered"}
```

```text
Context("owner-1", "task-1", 3, 2026-09-29T00:00:00Z)
prepare(validDraft, context, fixedClock) -> Preparation(snapshot, null)
snapshot.contractVersion() -> review-confirmation.v1
snapshot.digest() -> 64 lowercase hex SHA-256 characters (example value intentionally omitted)
verify(snapshot, validDraft, context, null, fixedClock)
  -> Verdict(CONFIRMATION_REJECTED, RECEIPT_REQUIRED)
verify(snapshot, validDraft, context, trustedCurrentReceipt, fixedClock)
  -> Verdict(CONFIRMATION_MATCHED, null) only when every binding check passes
```

`Receipt(ownerId, taskId, revision, digest, confirmed, confirmedAt)` is an input from a future trusted core-auth adapter. `confirmed=false` or `null` rejects as `CONFIRMATION_NOT_ACTIVE`; live withdrawal is represented only when the adapter fetches the **current** authoritative receipt state. `confirmedAt` must be at or after snapshot creation and at or before trusted now. A missing, stale or mismatched receipt rejects. / 확인 기록의 부재·철회·불일치·미래 시각은 거부합니다. 실제 철회 반영은 인증 담당자가 최신 상태를 조회해야 합니다.

`prepare` returns `snapshot=null` and a stable `reasonCode` such as `CONTEXT_REQUIRED`, `OWNER_ID_INVALID`, `TASK_ID_INVALID`, `REVISION_INVALID`, `CREATION_TIME_INVALID`, `CLOCK_REQUIRED` or `DRAFT_NOT_READY`. `verify` returns `CONFIRMATION_REJECTED` with codes including `OWNER_MISMATCH`, `TASK_MISMATCH`, `REVISION_MISMATCH`, `CREATION_TIME_MISMATCH`, `SNAPSHOT_DRAFT_NOT_READY`, `CURRENT_DRAFT_NOT_READY`, `CURRENT_DRAFT_CHANGED`, `RECEIPT_REQUIRED`, `RECEIPT_MALFORMED`, `CONFIRMATION_NOT_ACTIVE`, `CONFIRMATION_TIME_INVALID`, and `RECEIPT_*_MISMATCH`. Neither result has an executable or payment-authority state. / 결과 코드는 안정적인 거부 사유이며 집행 가능 상태를 만들지 않습니다.

## Digest and trust boundary / 다이제스트와 신뢰 경계

The SHA-256 input is a typed, length-delimited binary encoding of `review-confirmation.v1`, owner ID, task ID, positive revision, original creation instant (seconds and nanos), and every F008 draft field. Object keys are sorted before encoding. String values use exact UTF-16 code units, including exact amount spelling, Unicode normalization differences and unpaired surrogate code units; `10` and `10.0` differ. Booleans, null, objects and arrays have separate type markers. The digest does not use object identity or `hashCode`. This private encoding is **not JCS, EIP-712, a signature or proof of who confirmed**. A stored digest is meaningful only with trusted storage and a current authenticated receipt. / 정렬된 객체 키와 타입·길이 구분 인코딩으로 모든 필드와 문맥을 묶습니다. 다이제스트는 서명 또는 확인자 신원 증명이 아닙니다.

Use Java 21 (set `JAVA_HOME` to your local installation if needed). Run `./mvnw -q -Dtest=ReviewConfirmationBindingTest,ControllerReviewBindingTest test` for binding checks without a database. Run `./mvnw verify` with the PostgreSQL environment in the [API runbook](API_CONTRACT.md) for the complete gate. / Java 21에서 집중 검사를 실행하고, 전체 검사는 실행 문서의 PostgreSQL 환경을 준비합니다.

The digest is opaque server-produced data. Frontends display the exact server snapshot and return its reference through the agreed authenticated confirmation route; they should not reimplement this private binary encoding. / 프런트엔드는 서버의 정확한 초안을 표시하고 합의된 인증 경로로 참조값을 전달합니다. 다이제스트 인코딩을 따로 재구현하지 않습니다.
