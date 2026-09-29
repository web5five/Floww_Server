# Floww frontend E2E integration handoff / 프론트엔드 E2E 통합 인계

Updated: 2026-09-30 KST · Integration: Geondong Kim · Frontend: Sinwoo Park

**KO:** 백엔드–실제 Kiln–공개 Sepolia 지급–시뮬레이션 이행 확인까지 검증했습니다. 이제 프론트는 아래 API와 지갑 호출을 연결하면 됩니다. 이 문서는 실제 배포된 프론트에서 사용자가 구매를 완료했다는 뜻이 아닙니다. 약국·배송은 시뮬레이터이고 토큰 거래는 실제 Sepolia 거래입니다.

**EN:** The backend, live Kiln, public Sepolia payment and simulated fulfillment confirmation have been verified together. The frontend can now integrate the API and wallet sequence below. This is not yet acceptance of a purchase through the deployed frontend. Merchants/fulfillment are simulated; the Sepolia token transfers are real testnet transactions.

## 1. Frozen sources and verified scope / 기준 코드와 검증 범위

| Source / 대상 | Checked revision / 기준 |
|---|---|
| Backend / 백엔드 | [PR #41](https://github.com/web5five/Floww_Server/pull/41), main `6f1d3029885808bd35d706b47688b24166ed9273` |
| Contract / 컨트랙트 | [Floww_SmartContract](https://github.com/web5five/Floww_SmartContract/tree/d4e6a7d7b7635634b8a59f7c87bba91d3b311f9d), `d4e6a7d` |
| Frontend read-only comparison / 프론트 대조 | main `a8a1f459d04f589ca4488c73912592890d2c0c5c` (includes PR #7) |
| Optional Magic / 선택 Magic 연결 | [PR #31](https://github.com/web5five/Floww_Server/pull/31) merged as `1885f28e9b2c8a61f68281f98aafd333b35766bd`; real OTP/login/logout/reconnect passed locally, required CI passed |
| Evidence / 실행 증거 | [55 independent public-Sepolia checks](F031_INDEPENDENT_SEPOLIA.md), [machine-readable result](evidence/f031/sepolia-e2e-result.json) |

192 Java/PostgreSQL tests passed for PR41. The public-chain run used a locally hosted backend and a scripted test-owner wallet; it is distinct from a human frontend wallet run. The latest hosted Preview is being tested separately. / PR41의 Java/PostgreSQL 192개 검사가 통과했고 공개 체인 검증은 로컬 백엔드와 테스트 소유자 스크립트로 진행했습니다. 배포 Preview 검증과 사용자 프론트 검증은 별도입니다.

## 2. Environment and access / 환경·접속

**Snapshot:** `https://floww-server-demo.vercel.app/` responds 200 and health is UP, but the currently public deployment has AI/chain execution disabled. It is **not the full execution endpoint**. Protected Preview has live features and deployment validation is in progress. The deployment owner must provide the usable backend base URL and approved frontend origin before claiming hosted E2E readiness. A Vercel redirect/SSO page is an access problem, not a successful business API response.

**현재 상태:** 공개 주소는 안내 페이지이며 아직 전체 실행용 주소가 아닙니다. 실제 기능을 켠 Preview 검증과 팀 접근 경로 준비가 진행 중입니다. 주소·프론트 origin을 확정한 뒤 연결하고, HTML/302 응답을 API 성공으로 처리하지 마세요.

Frontend server configuration / 프론트 서버 설정:

```dotenv
FLOWW_API_BASE_URL=<reachable-backend-origin-without-path>
FLOWW_WALLET_AUTH_ENABLED=true
FLOWW_WALLET_AUTH_MODE=team-jwt
FLOWW_BUSINESS_JWT_ENABLED=true
FLOWW_WALLET_CHAIN_IDS=11155111
FLOWW_SESSION_SECRET=<server-only-64-hex-characters>
```

- Java `FLOWW_WALLET_ORIGIN` must equal the **frontend's actual origin**, because the browser validates the exact SIWE origin. Coordinate with the deployment owner; do not replace it with an arbitrary Host header. / Java의 로그인 origin은 실제 프론트 origin과 일치시킵니다.
- Use the existing encrypted HttpOnly BFF session. The server-side BFF adds `Authorization: Bearer <owner JWT>`; do not copy the token into browser storage. / 기존 BFF 세션을 유지합니다.
- Backend readiness also requires chain mode, configured token/merchant registry, executor/reporter and Kiln. Those keys stay server-only. The owner's wallet remains the browser wallet. / 실행자·reporter·Kiln 키는 서버 전용이고 사용자 지갑 개인키를 서버에 전달하지 않습니다.
- Read `mandate.asset` and prepared account values. Expected test network: Sepolia `11155111` / `0xaa36a7`; token `0x1390c8745Eb49069afD3b89393997e3FA14614f5`, decimals `6`. Never silently use the older source fallback token. / 응답과 현재 설정의 체인·토큰을 확인합니다.
- MetaMask is sufficient for the first integrated purchase. Magic login does not block this integration. Its standalone example uses a memory JWT; frontend adoption must adapt it to the existing BFF challenge/verify/session contract instead of inventing another session. / 우선 MetaMask로 통합하고 Magic은 같은 서버 로그인 계약에 맞춰 연결합니다.

## 3. Frontend changes required now / 현재 프론트에서 변경할 곳

These are source-derived handoff items, **not changes made to the frontend by this document**. / 아래 항목은 읽어서 확인한 수정 지점이며 프론트 코드를 대신 수정한 것은 아닙니다.

| File at frontend revision above | Required change / 필요한 수정 |
|---|---|
| `src/lib/api/task-proxy.ts` | Add exact allowlisted account POST routes below, `GET account/funding`, `POST attempts` for explicit/manual DENY tests, and `POST orders`. Current mutation allowlist only covers quotes/AI/reject/cancel. / 새 실행 경로를 명시적으로 허용합니다. |
| Same BFF / 같은 파일 | Current code rejects any body on a non-root POST. Implement route-specific JSON shapes for prepare/bind/signature/orders/attempts while retaining origin/auth/size checks; AI must retain an actually empty body. Forward `Idempotency-Key` for orders as well as Task creation. / 경로별 본문 검증과 주문 멱등 키 전달이 필요합니다. |
| Same response sanitizer / 응답 필터 | It currently removes every property named `token`. Applied unchanged to `approval-request`, this would remove **`typedData.message.token`**, an essential public contract address. Preserve the exact typed-data structure and public token address; continue stripping actual credentials. Do not mutate typed data before signing. / 토큰 주소와 자격증명을 구분해야 합니다. |
| `src/lib/api/task-types.ts`, `task-client.ts` | Add account/funding/approval DTOs and actions; preserve integer strings, nulls and separate state machines. Reuse `ai-proposal`'s returned attempt; do not create a duplicate attempt for it. / DTO·호출 함수를 추가합니다. |
| `src/lib/api/account-evidence.ts` | Existing read-only PAID/COMPLETED predicates are useful. Extend for execution controls, approval state and funding; do not replace the receipt-based completion checks with a transaction-hash-only check. / 기존 증거 판정을 유지합니다. |
| Purchase UI + wallet adapter / 구매 화면·지갑 | Implement owner deployment, typed-data signature, token allowance and account funding; server executes approve/payment/fulfillment. Persist only public recovery identifiers across refresh. / 지갑·서버 역할을 아래 순서대로 연결합니다. |

No new Java API, smart contract, database table or parallel approval schema is needed for this frontend slice. / 이 연결을 위해 Java API·DB·컨트랙트·서명 형식을 새로 설계할 필요는 없습니다.

## 4. Request sequence / 호출 순서

Task creation uses exactly **`/api/v1/tasks`** (no trailing slash); subsequent `/{taskId}/...` paths below are relative to that base. Require owner USER JWT. JSON requests use `Content-Type: application/json`; `—` means no body. Task creation and order creation require distinct stable `Idempotency-Key` values. / Task 생성 경로에는 끝 슬래시를 붙이지 않고, 업무 API는 소유자 JWT와 각각의 멱등 키를 사용합니다.

| # | Method/path | Body / 본문 | Result and next gate / 결과·다음 조건 |
|---|---|---|---|
| 1 | `POST /api/v1/tasks` | Task input below | `TaskView`, normally `AWAITING_APPROVAL`; no spending authority. / 승인 대기 |
| 2 | `POST /{taskId}/quotes` | — | `{taskId, mandateVersion, quotes[]}`; render cost/conditions. / 견적 표시 |
| 3 | `POST /{taskId}/ai-proposal` | **No bytes: not even `{}`** | `{proposal, attempt, reusedAttempt}`. Continue only with a non-null policy-ALLOW attempt. / 허용된 시도만 다음 단계 |
| 4 | `POST /{taskId}/account/prepare` | `{"attemptId":"<UUID>","ownerAddress":"0x<connected-owner>"}` | `AccountView.state=PREPARED`; freeze selected purchase; obtain `deploymentData`. / 구매 내용 고정 |
| 5 | **Owner wallet transaction** / 소유자 지갑 | Contract creation using prepared data | Wait successful receipt; read `contractAddress`. / 배포 영수증 확인 |
| 6 | `POST /{taskId}/account/bind` | `{"accountAddress":"0x<receipt-contract>","deploymentTxHash":"0x<hash>"}` | `BOUND`; server validates sender/init code/runtime/getters. / 서버 배포 검증 |
| 7 | `POST /{taskId}/account/approval-request` | — or `{}` | `{typedData,digest,nonce,expiresAt}`; display purchase and request signature. / 구매 서명 준비 |
| 8 | **Owner wallet signature**, then `POST /{taskId}/account/signature` | `{"signature":"0x<65-byte-signature>"}` | `SIGNED`; Task ACTIVE is **not yet on-chain approval or payment**. / 서명 검증 |
| 9 | `POST /{taskId}/account/approve` | — or `{}` | `APPROVAL_UNKNOWN`; call reconcile until `APPROVED`. / 승인 거래 확인 |
| 10 | `GET /{taskId}/account/funding` | — | `FundingView`; owner sends allowance then fund transactions. / 사용자 자금 준비 |
| 11 | `POST /{taskId}/orders` | `{"attemptId":"<same UUID>"}` + stable order key | `OrderView`, Task EXECUTING; **not paid yet**. / 주문만 생성 |
| 12 | `POST /{taskId}/account/payment` | — or `{}` | `PAYMENT_UNKNOWN`; reconcile until `PAID` with payment operation VERIFIED. / 지급 확인 |
| 13 | `POST /{taskId}/account/fulfillment` | — or `{}` | `FULFILLMENT_UNKNOWN`; reconcile until `COMPLETED`. / 시뮬레이션 이행 확인 |
| 14 | `GET /{taskId}`, `GET /{taskId}/account` | — | Show final Task/account/evidence; require both completion states and verified events. / 최종 상태 표시 |

`POST /{taskId}/account/reconcile` accepts no body or `{}`. It reads the previously persisted operation/receipt; it is not a second payment instruction. Poll with bounded backoff, single in-flight request and visibility/cancel handling. The polling schedule is a frontend recommendation, not a new API contract.

`GET /{taskId}/events?after=0&limit=50` → `{events,nextCursor,hasMore}`. Use cursor pagination; this route is JSON polling, **not SSE**. / 이벤트는 커서 기반 JSON 조회입니다.

For explicit/manual selection or the DENY demonstration: `POST /{taskId}/attempts` with `{"quoteId":"<server-quote-id>","proposedBy":"USER"}` returns an `AttemptView`; DENY can be HTTP201, so inspect `policy.decision`. The AI route already persists/returns its selected attempt. Do **not** call attempts again for that result. / 수동 시도와 AI 시도를 중복 생성하지 않습니다.

**Do not call legacy** `/{taskId}/attempts/{attemptId}/approval` or `/{taskId}/mandate/confirm` for chain execution. With chain mode enabled these return `CHAIN_MODE_REQUIRED`. Use only the TaskAccount sequence above. / 체인 모드에서는 기존 PurchaseApproval 경로를 사용하지 않습니다.

## 5. Inputs and values / 입력·금액

Illustrative Task request; generate a fresh future timestamp rather than copying an expired literal. / 미래 시각을 새로 계산합니다.

```ts
const input = {
  goal: 'Buy one pack of acetaminophen within 60 fUSDC',
  itemId: 'acetaminophen-500mg-10',
  maxAmountBaseUnits: '60000000',
  expiresAt: new Date(Math.floor((Date.now() + 6 * 3600_000) / 1000) * 1000).toISOString(),
  allowedMerchantIds: ['pharmacy-a', 'pharmacy-b', 'pharmacy-c'], // optional
};
```

- Amounts are base-unit decimal **strings**, not JSON numbers. Use BigInt/string arithmetic: `"23500000"` =23.5 fUSDC; Task cap `"60000000"` =60. The prepared account `maxSpend` is the **selected total**, not the whole Task cap. / Task 한도와 이번 구매 금액을 구분합니다.
- Current simulator: A23.5 → ALLOW, B64 → BUDGET_EXCEEDED, C19 with different quoted payee → RECIPIENT_NOT_ALLOWED for the60-cap acetaminophen scenario. Read live quote IDs/amounts; do not hardcode authorization data. / 실제 견적 응답을 사용합니다.
- A promises fulfillment in2hours. A short Task deadline can legitimately yield no eligible quote. Six hours above is a test input recommendation. / 너무 짧은 기한은 정상적으로 후보 없음이 됩니다.
- Quotes expire after15minutes. Account expiry is `min(quote expiry, mandate expiry)`. Begin with fresh quotes; an expired prepared/bound account requires a new Task/account rather than changing immutable data. / 승인·배포 전에 남은 견적 시간을 표시합니다.
- Only known Task input fields are accepted. The current frontend BFF rejects `allowedMerchantIds`; omit it for the default registry or add its explicit validation. / 선택 필드를 전송하려면 BFF도 맞춰야 합니다.

## 6. Exact wallet actions / 지갑 작업

Use the same connected owner and Sepolia throughout. Recheck account/chain before each action, invalidate the local flow on account/chain change, and never substitute a server executor as user. / 전 과정에서 동일 사용자 지갑과 Sepolia를 확인합니다.

```ts
// Illustrative EIP-1193 calls. Implement receipt waiting, errors and UI state separately.
const deploymentHash = await provider.request({
  method: 'eth_sendTransaction',
  params: [{ from: ownerAddress, data: prepared.deploymentData, value: '0x0' }],
}); // contract creation: omit `to`; do not replace the provided bytecode

// After deployment receipt and backend bind:
const signature = await provider.request({
  method: 'eth_signTypedData_v4',
  params: [ownerAddress, JSON.stringify(approval.typedData)],
}); // send only {signature} to account/signature

// After account state APPROVED; read GET account/funding:
const allowanceHash = await provider.request({
  method: 'eth_sendTransaction',
  params: [{ from: ownerAddress, to: funding.tokenAddress,
    data: funding.tokenApproveData, value: '0x0' }],
});
// Wait successful allowance receipt before calling fund.
const fundHash = await provider.request({
  method: 'eth_sendTransaction',
  params: [{ from: ownerAddress, to: funding.accountAddress,
    data: funding.accountFundData, value: '0x0' }],
});
// Wait successful fund receipt, then re-read funding/balance before ordering/payment.
```

User needs Sepolia ETH for deployment/allowance/funding gas and fUSDC for the purchase. Faucet/token setup is supplied by the chain/integration owner; there is no invented public faucet API in this handoff. / 가스와 테스트 토큰은 별도 준비합니다.

Signing schema: `FlowwTaskAccount` / version `1` / chain11155111 / verifyingContract=bound account; primaryType `MandateApproval`. Its message contains `owner, taskId, reviewSnapshotDigest, token, recipient, executor, fulfillmentReporter, maxSpend, expiresAt, nonce`. The signed field is **`nonce`**, not `authorizationNonce`; the latter is a contract getter. `paymentId` is not part of this signature. Pass the server typedData unchanged. / 서버 응답을 새 서명 형식으로 재조립하지 않습니다.

SIWE login is a separate `personal_sign` action. “Approve once” refers to purchase authorization; deployment, ERC20 allowance and funding still require wallet transactions. / 로그인 서명·구매 승인·자금 거래를 하나로 표현하지 않습니다.

## 7. Response DTOs and authority / 응답 DTO·판정

Actual source: [TaskViews](../src/main/java/com/floww/server/task/application/TaskViews.java), [TaskAccountService records](../src/main/java/com/floww/server/taskaccount/TaskAccountService.java), [TaskAccountController](../src/main/java/com/floww/server/taskaccount/TaskAccountController.java). These new account routes may be ahead of the older OpenAPI document; do not infer their absence from a stale generated contract. / 위 코드와 이 문서로 연결하고 구 OpenAPI에 없는 경로를 임의로 대체하지 않습니다.

```ts
type AccountState = 'PREPARED' | 'BOUND' | 'SIGNED' | 'APPROVAL_UNKNOWN'
  | 'APPROVED' | 'PAYMENT_UNKNOWN' | 'PAID' | 'FULFILLMENT_UNKNOWN' | 'COMPLETED';
type OperationState = 'UNKNOWN' | 'VERIFIED' | 'REVERTED' | 'MISMATCH';
interface AccountView {
  taskId: string; attemptId: string; state: AccountState; ownerAddress: string;
  accountAddress: string | null; deployTxHash: string | null;
  chainTaskId: string; reviewSnapshotDigest: string; amountBaseUnits: string;
  tokenAddress: string; recipientAddress: string; executorAddress: string;
  fulfillmentReporter: string; quoteExpiresAt: string; expiresAt: string;
  deploymentData: string | null; approvalDigest: string | null;
  approvalTxHash: string | null; approvalOperationState: OperationState | null;
  paymentId: string | null; paymentTxHash: string | null;
  paymentOperationState: OperationState | null; paymentVerifiedAt: string | null;
  fulfillmentId: string | null; fulfillmentEvidenceMode: string | null;
  fulfillmentEvidenceHash: string | null; fulfillmentTxHash: string | null;
  fulfillmentOperationState: OperationState | null; fulfillmentVerifiedAt: string | null;
}
interface FundingView {
  accountAddress: string; tokenAddress: string; amountBaseUnits: string;
  tokenApproveData: string; accountFundData: string;
  accountTokenBalanceBaseUnits: string; mandateApproved: boolean;
}
```

`approval-request` is `{typedData, digest, nonce, expiresAt}`; nonce is a decimal string, expiresAt ISO UTC. Do not convert uint256 fields to JavaScript Number. Account response has `state`; Task has `status`; neither should be flattened into the other's enum. / 큰 정수와 상태 체계를 그대로 유지합니다.

| Layer / 계층 | Meaning / 의미 |
|---|---|
| `proposal.status` | `PROPOSED`, `CLARIFICATION_REQUIRED`, `NO_CANDIDATE`, `REJECTED`, `MODEL_FAILURE`; recommendation outcome, not authorization. / 제안 결과 |
| `attempt.policy.decision` | ALLOW/DENY for one attempt; DENY is not automatically terminal Task DECLINED. / 시도별 정책 |
| `Task.status` | DRAFT, AWAITING_APPROVAL, ACTIVE, EXECUTING, COMPLETED, DECLINED, FAILED, EXPIRED, CANCELLED. / 작업 상태 |
| `Account.state` | Chain workflow states above. There is **no FUNDED account state**; inspect funding balance and receipt instead. / 체인 진행 상태 |
| `*OperationState` | VERIFIED only after server receipt/event verification. UNKNOWN is not failure or success. / 거래 검증 상태 |

Render “Paid” only with payment operation VERIFIED + payment hash + paymentVerifiedAt. Render “Completed” only when Task and account are COMPLETED and payment/fulfillment both have VERIFIED state, hashes and verification timestamps. Use `fulfillmentEvidenceMode=local_pharmacy_simulator` to keep the delivery description truthful. These are receipt-inclusion checks, **not a certified finality/reorg policy**. / 해시만 있다고 완료 처리하지 않고, 실제 배송으로 표현하지 않습니다.

## 8. Recovery and errors / 재개·오류 처리

- Persist Task ID, attempt ID, account address, public transaction hashes and request idempotency keys for recovery; never credentials/signatures/raw signed transactions. Refresh through Task + account + events. / 새로고침 후 서버 상태로 복구합니다.
- An `*_UNKNOWN` state or network timeout requires reconciling the **same** account/payment, never creating a new Task/order just to retry payment. Stop automatic loops on REVERTED/MISMATCH and request inspection. / 결과 미확인은 재지급 사유가 아닙니다.
- Owner-wallet deployment/allowance/fund hashes must also survive UI reload; inspect their receipts before resending. The backend cannot infer a never-submitted deployment hash from a frontend timeout. / 사용자 지갑 거래도 중복 전송하지 않습니다.
- `401 UNAUTHORIZED` → re-login. `404 TASK_NOT_FOUND` can mean another owner's Task. `409 CHAIN_NOT_READY` is broad: disabled runtime, absent account, configuration/receipt/funding mismatch; show pending/unavailable and inspect state, not “payment failed/refunded”. / 오류를 과장해 판정하지 않습니다.
- `CHAIN_MODE_REQUIRED` → wrong legacy approval path. `APPROVAL_INVALIDATED`, `MANDATE_VERSION_MISMATCH`, stale/expired quote → reread current data and request new approval where valid. Never reuse a signature with changed purchase terms. / 변경된 조건에 구 서명을 재사용하지 않습니다.
- Non-PROPOSED AI results and null attempts must not open a signing prompt. Display a safe reason and allow explicit retry/clarification; avoid automatic cost-bearing model retries. / AI 실패·후보 없음에서 서명하지 않습니다.
- STOP should disable further local actions immediately. Server cancel/reject does **not** prove on-chain revocation, undo an already broadcast transaction or trigger a refund. Show those outcomes separately and reconcile in-flight transactions. / 중지·온체인 취소·환불을 구분합니다.
- General error shape: `{reasonCode, code, message:{ko,en}, taskId, attemptId, retryable}`. Policy DENY can arrive as a successful attempt response; wallet rejection is a provider error, not a server approval. / HTTP·정책·지갑 오류를 구분합니다.

## 9. Frontend acceptance checklist / 프론트 인수 순서

1. **Connection:** correct base/origin, real wallet JWT/BFF cookie, owner-only Task reads; another owner rejected. / 연결·소유권
2. **Proposal:** create60-cap Task, live quotes, real Kiln A selection, reuse returned ALLOW attempt. / 요청·추천
3. **Review:** show item, selected total23.5, Task cap60, expiry and merchant/payee; freeze exactly those terms. / 사용자 확인
4. **Wallet:** owner deploy → bind → exact typed signature → executor approve → reconcile → allowance → fund; no hidden mainnet switch. / 지갑 단계
5. **Execution:** idempotent order → exact payment → reconcile PAID → reporter fulfillment → reconcile COMPLETED. / 지급·이행
6. **Resume:** refresh at pending payment, resume the same Task/paymentId/hash; no second transfer. / 중복 방지
7. **DENY:** B over budget and C wrong recipient never open a purchase signature/transaction prompt; account/operation/tx stay absent. Public-run evidence supports zero signed-operation rows and unchanged executor nonce; direct public signer-call counters were not instrumented. / 차단 증거 범위를 정확히 기록
8. **UX:** reject signature, insufficient gas/token, expiry, account/network change, timeout, STOP and wrong-owner recovery; do not display success from fixtures. / 실패·복구
9. **Hosted acceptance:** repeat on the actual shared frontend+backend origins, record their SHAs, Task ID and explorer hashes. Local scripts/CI do not close this item. / 배포 환경 최종 확인

Remaining hardening is tracked separately: confirmation depth/reorg behavior, broader crash/revoke mutation matrix and per-request public signer instrumentation. Do not claim Michael's entire review closed by this handoff. / 최종성·추가 장애 검사는 별도이며 전체 리뷰 완료로 확대하지 않습니다.

## 10. Who connects what / 다음 담당

| Owner / 담당 | Next deliverable / 다음 산출물 |
|---|---|
| Sinwoo Park / 박신우 | BFF route/body/typedData handling, client DTOs, wallet UI and recovery, actual browser acceptance. / 프론트 연결 |
| Geondong Kim / 김건동 | Backend API integration support, deployment access readiness, current evidence and combined E2E verification. / 통합·검증 |
| Taeheon Choi / 최태헌 | Wallet/chain transaction issues, token/gas and contract-owner review; frontend must use the existing contract data. / 체인 지원 |
| Ria Choi / 최리아 | Review unexpected common API/auth/DB discrepancies against latest main; this handoff requests no package restructuring. / 공통 서버 호환성 |
| Michael | Review visible journey and truthful scenario/evidence for the demo. / 시나리오·증거 표현 |

This table states integration handoff needs; it does not assert teammates accepted new assignments. / 필요한 협업을 정리한 것이며 새 업무 수락을 대신 선언하지 않습니다.

Completed-run reference: Task `5665a02a-2300-4fab-866f-02a60ae57ead`, [payment23.5fUSDC](https://sepolia.etherscan.io/tx/0x2e3110192ca84dbcafb5d6a0e925dd40cdd5fc5c700d161261afa10979281708), [fulfillment event](https://sepolia.etherscan.io/tx/0x8b6af8b662b87b86896e7179483da8fe94b11c60d6494fd20c6fc198f669ba60). This Task belongs to the controller's isolated local database; a new cloud database cannot retrieve it merely by copying its ID. / 완료된 Task ID를 새 배포 DB의 데이터로 오해하지 않습니다.

Confluence: PENDING_SYNC. This repository document is the implementation handoff; no private chat, keys or raw signed payloads are included.
