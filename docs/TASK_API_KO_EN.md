# Task API `/api/v1/tasks` — 한/영 연동 문서 (Issues #33 · #34 · #35, parent #32)

상태 / Status: **구현·로컬 검증 완료, 팀 계약 검토 대기** — implemented and verified locally (PostgreSQL + local pharmacy simulator + local test wallet signatures). Real Kiln calls, Sepolia payment and merchant fulfillment are **not** part of this slice.

근거 / Sources: Confluence “정책과 결정” (2026-09-30), “데이터 명세서”, “개발 문서 · TaskStatus”, API-11~14·16.

## 1. 흐름 / Flow

```
POST /tasks ─▶ AWAITING_APPROVAL (mandate v1 DRAFT, no spending authority)
   └─ POST /quotes            약국 3곳 견적 스냅샷 / 3 pharmacy quote snapshots
   └─ GET  /proposal-context  AI(#18) 입력 / AI input
   └─ POST /attempts          quote 제안 → 정책 ALLOW / DENY (attempt 단위)
   └─ POST /attempts/{id}/approval   EIP-712 typed data + 1회용 nonce
   └─ POST /mandate/confirm   서명 검증 → mandate CONFIRMED ─▶ ACTIVE
POST /orders (Idempotency-Key) ─▶ EXECUTING   (판매자 주문만, paymentStatus=NOT_ATTEMPTED)
```

- KO: 모델은 `quoteId`만 제안합니다. 가격·수취인·한도는 서버 스냅샷과 레지스트리에서만 가져옵니다. 결정론적 정책과 사용자 EIP-712 서명이 있어야 주문할 수 있습니다.
- EN: The model proposes only a `quoteId`. Price, recipient and limits come from server snapshots and the merchant registry. An order needs a deterministic policy ALLOW plus the user's EIP-712 signature.

모든 요청은 client JWT가 필요합니다 (`Authorization: Bearer <accessToken>`). 타인 Task는 404 `TASK_NOT_FOUND`. dev 프로필의 alice/bob 토큰은 users 행이 없어 Task API에서 401입니다.
All routes require a client JWT. Other users' tasks return 404. The dev-profile alice/bob tokens get 401 here (no `users` row).

## 2. 금액 / Amounts (#16)

- 금액은 **토큰 최소 단위 10진 정수 문자열**입니다. fUSDC decimals=6 → `"23500000"` = 23.5 fUSDC.
- JSON 숫자, 소수점, 부호, 공백, 앞자리 0은 400 `INVALID_INPUT`. 서버 내부 계산은 `BigInteger`, DB는 `NUMERIC(78,0)`.
- 승인 한도(`maxAmountBaseUnits`)는 **Task 전체 누적 지출 상한**입니다: 기존 주문 합계 + 새 견적 ≤ 한도.
- Asset: `chainId=11155111`, `tokenAddress=0x84b494ff145a545d286321691a9b4febe6947d6a`, `tokenDecimals=6` (env `FLOWW_TASK_*`).

## 3. 약국 시뮬레이터 / Pharmacy simulator (#33, #17)

`com.floww.server.merchant.PharmacySimulator` — 서버 내장, 결정적, 위임을 검사하지 않음 / in-process, deterministic, does not enforce the mandate.

| merchantId | acetaminophen-500mg-10 상품+배송 = 총액 | ibuprofen-200mg-20 | 레지스트리 수취인 / registry payee | 데모 역할 / demo role |
|---|---|---|---|---|
| `pharmacy-a` | 20.5 + 3.0 = **`23500000`** | 12.0 + 3.0 | `…f10aa001` | ✅ 60 fUSDC 위임 안 / within mandate |
| `pharmacy-b` | 52.0 + 12.0 = **`64000000`** | 재고 없음 / out of stock | `…f10aa002` | ⛔ `BUDGET_EXCEEDED` |
| `pharmacy-c` | 17.0 + 2.0 = **`19000000`** | 9.0 + 2.0 | `…f10aa003` (견적 payTo `…badc0de3`) | ⛔ `RECIPIENT_NOT_ALLOWED` |

- quoteId = `qt_{a|b|c}_` + sha256(taskId, merchant, item, quotedAt)[:16]; 15분 유효. 살아 있는 스냅샷이 있으면 같은 quoteId를 다시 돌려줍니다.
- orderId = `ord_{a|b|c}_` + sha256(quoteId)[:16]; 이행 결과 `fulfill(orderId)` = `DELIVERED` + `ful_…` (결정적). 이행은 지급 확정 뒤에만 호출해야 하며 현재 API에는 연결하지 않았습니다.
- 수취 주소 기본값은 키가 없는 placeholder입니다. 테스트넷 지급 전에 `FLOWW_PHARMACY_{A,B,C}_RECIPIENT`를 체인 담당이 채웁니다. Defaults are keyless placeholders; the chain owner must set real testnet payees before any payment test.

## 4. 요청·응답 예시 / Examples

### POST /api/v1/tasks  (`Idempotency-Key` 필수)

```json
{
  "goal": "Buy one pack of acetaminophen within 60 fUSDC",
  "itemId": "acetaminophen-500mg-10",
  "maxAmountBaseUnits": "60000000",
  "expiresAt": "2026-10-02T15:00:00Z",
  "allowedMerchantIds": ["pharmacy-a", "pharmacy-b", "pharmacy-c"]
}
```

`allowedMerchantIds`는 선택(기본: 레지스트리 활성 판매자 전체). `confirmed`, `ownerId`, `role` 등 알 수 없는 필드는 400. `expiresAt`은 미래이고 30일 이내.
201 = 새 Task, 200 = 같은 키·같은 본문 재요청, 409 `IDEMPOTENCY_CONFLICT` = 같은 키·다른 본문.

### Task 객체 / Task object

```json
{
  "taskId": "2f9c1d4e-…",
  "ownerId": "7d1e…",
  "status": "EXECUTING",
  "statusReasonCode": null,
  "goal": "Buy one pack of acetaminophen within 60 fUSDC",
  "mandate": {
    "mandateId": "7b1e2c7a-…", "version": 1, "status": "CONFIRMED",
    "goal": "…", "itemId": "acetaminophen-500mg-10",
    "maxAmountBaseUnits": "60000000", "consumedBaseUnits": "23500000", "remainingBaseUnits": "36500000",
    "budgetScope": "TASK_CUMULATIVE",
    "asset": { "chainId": 11155111, "tokenAddress": "0x84b4…7d6a", "tokenDecimals": 6 },
    "allowedRecipients": [{ "merchantId": "pharmacy-a", "recipientAddress": "0x…f10aa001" }],
    "allowedActions": ["PURCHASE"],
    "expiresAt": "2026-10-02T15:00:00Z", "confirmedAt": "2026-09-30T01:10:00Z",
    "confirmationMethod": "EIP712", "authorizationReference": "0x<digest>"
  },
  "attempts": [
    {
      "attemptId": "9e8d…", "mandateId": "7b1e…", "mandateVersion": 1,
      "quoteId": "qt_b_…", "merchantId": "pharmacy-b", "proposedBy": "AI", "status": "BLOCKED",
      "amountBaseUnits": "64000000", "recipientAddress": "0x…f10aa002",
      "policy": { "decision": "DENY", "reasonCode": "BUDGET_EXCEEDED",
                  "message": { "ko": "승인한 예산을 초과합니다", "en": "Budget exceeded" },
                  "policyVersion": "task-policy-v1", "decidedAt": "…" },
      "approval": null, "order": null, "payment": { "status": "NOT_ATTEMPTED", "txHash": null },
      "createdAt": "…"
    },
    {
      "attemptId": "5c4b…", "quoteId": "qt_a_…", "merchantId": "pharmacy-a", "status": "ORDERED",
      "policy": { "decision": "ALLOW", "reasonCode": null, "message": null, "policyVersion": "task-policy-v1", "decidedAt": "…" },
      "approval": { "method": "EIP712", "digest": "0x…", "signerAddress": "0x…", "signedAt": "…" },
      "order": { "orderId": "…", "merchantOrderId": "ord_a_…", "status": "ACCEPTED", "paymentStatus": "NOT_ATTEMPTED",
                 "amountBaseUnits": "23500000", "recipientAddress": "0x…f10aa001", "…": "…" },
      "payment": { "status": "NOT_ATTEMPTED", "txHash": null }
    }
  ],
  "createdAt": "…", "updatedAt": "…", "completedAt": null
}
```

### POST /attempts

```json
{ "quoteId": "qt_a_0123456789abcdef", "proposedBy": "AI", "recipientAddress": "0x…(선택, optional)" }
```

201 + attempt. DENY도 201이며 `policy.decision`으로 구분합니다 (HTTP 오류 아님). DENY attempt는 승인·주문할 수 없습니다.
`recipientAddress`는 신뢰하지 않는 제안값으로, 레지스트리와 다르면 `RECIPIENT_NOT_ALLOWED`. 이 Task의 견적이 아닌 quoteId는 `UNKNOWN_QUOTE_ID` DENY로 기록됩니다.

정책 순서 / policy order (`task-policy-v1`, first failure wins): `MANDATE_EXPIRED` → `UNKNOWN_QUOTE_ID` → `QUOTE_STALE` → `RECIPIENT_NOT_ALLOWED` (registry missing, merchant payTo ≠ registry, proposed recipient ≠ registry, not in mandate) → `ITEM_NOT_ALLOWED` → `OUT_OF_STOCK` → `CURRENCY_MISMATCH` → `BUDGET_EXCEEDED` (cumulative).

### POST /attempts/{attemptId}/approval → EIP-712

```json
{
  "taskId": "…", "attemptId": "…", "mandateId": "…", "version": 1,
  "nonce": "0x<32 bytes>", "digest": "0x<eip712 digest>", "expiresAt": "…",
  "typedData": {
    "types": {
      "EIP712Domain": [{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"}],
      "PurchaseApproval": [
        {"name":"taskId","type":"string"}, {"name":"mandateId","type":"string"}, {"name":"version","type":"uint256"},
        {"name":"merchantId","type":"string"}, {"name":"quoteId","type":"string"},
        {"name":"recipientAddress","type":"address"}, {"name":"tokenAddress","type":"address"},
        {"name":"amountBaseUnits","type":"uint256"}, {"name":"maxAmountBaseUnits","type":"uint256"},
        {"name":"expiresAt","type":"uint256"}, {"name":"nonce","type":"bytes32"}]
    },
    "primaryType": "PurchaseApproval",
    "domain": { "name": "Floww", "version": "1", "chainId": 11155111 },
    "message": { "taskId": "…", "mandateId": "…", "version": "1", "merchantId": "pharmacy-a", "quoteId": "qt_a_…",
                 "recipientAddress": "0x…f10aa001", "tokenAddress": "0x84b4…7d6a",
                 "amountBaseUnits": "23500000", "maxAmountBaseUnits": "60000000",
                 "expiresAt": "1790000000", "nonce": "0x…" }
  }
}
```

- `expiresAt` = min(견적 만료, mandate 만료), unix seconds. `maxAmountBaseUnits` = Task 누적 한도, `amountBaseUnits` = 이 견적 총액.
- `verifyingContract`는 결정 문서에 없어 넣지 않았습니다. Task Account 컨트랙트가 정해지면 체인 담당과 함께 추가합니다 (domain 변경 = 새 서명 필요).

프론트 서명 / frontend signing (서버가 준 `typedData`를 수정 없이 사용):

```js
const signature = await provider.request({
  method: "eth_signTypedData_v4",
  params: [account, JSON.stringify(approval.typedData)],
});
await fetch(`/api/v1/tasks/${taskId}/mandate/confirm`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` }, // server-side route only
  body: JSON.stringify({ mandateId: approval.mandateId, version: approval.version,
                         attemptId: approval.attemptId, nonce: approval.nonce, signature }),
});
```

서명 지갑은 JWT 사용자의 `wallet_identities`에 연결된 주소여야 합니다 (지갑 로그인 AUTH-04 또는 향후 AUTH-10 지갑 연결).
The signer must be a wallet linked to the JWT user.

### POST /mandate/confirm 검증 순서 / verification order

1. Task `AWAITING_APPROVAL`, mandateId·version = 현재 버전 (`MANDATE_VERSION_MISMATCH`)
2. nonce가 이 Task·attempt·mandate 것이고 미소비 (`APPROVAL_NONCE_INVALID`), 미만료 (`APPROVAL_EXPIRED`)
3. 저장된 mandate·quote·레지스트리로 typed data를 다시 만들어 digest 일치 (`APPROVAL_INVALIDATED`)
4. 서명에서 signer 복구 — 65바이트, v 27/28·0/1, low-s (`SIGNATURE_INVALID`)
5. signer ∈ 소유자 지갑 (`SIGNER_NOT_TASK_OWNER`)
6. 정책 재검증: mandate·견적 만료, 수취인, 누적 한도 (`APPROVAL_INVALIDATED`)
7. `UPDATE approval_nonces … WHERE consumed_at IS NULL` 로 원자적 1회 소비 → `mandate_approvals`에 typed data·digest·signature·signer·signedAt 저장

실패한 서명 시도는 nonce를 소비하지 않습니다. 성공 후 같은 nonce 재사용은 거절됩니다.

### POST /orders (`Idempotency-Key` 필수)

```json
{ "attemptId": "5c4b…" }
```

Task `ACTIVE` + attempt `APPROVED` 필요. 주문 직전 정책을 다시 검사합니다(`APPROVAL_INVALIDATED`). 201 새 주문 / 200 같은 키·같은 본문 / 409 `IDEMPOTENCY_CONFLICT` 같은 키·다른 본문. 한 attempt에는 주문 하나(`UNIQUE(attempt_id)`). 주문은 결제·서명을 하지 않으며 `paymentStatus`는 `NOT_ATTEMPTED`입니다.

### 기타 / Others

| method | path | 설명 |
|---|---|---|
| `GET` | `/api/v1/tasks?limit=20` | 내 Task 목록 (최대 50) |
| `GET` | `/api/v1/tasks/{taskId}` | 상세. 기한이 지난 mandate면 조회 시 `EXPIRED`(`MANDATE_EXPIRED`) |
| `POST` | `/api/v1/tasks/{taskId}/mandate/revisions` | 본문 = 생성 필드 + `baseVersion`. version+1, 이전 버전 REVOKED, 미주문 attempt SUPERSEDED, ACTIVE → AWAITING_APPROVAL |
| `POST` | `/api/v1/tasks/{taskId}/mandate/reject` | AWAITING_APPROVAL → DECLINED (`USER_REJECTED`) |
| `POST` | `/api/v1/tasks/{taskId}/cancel` | ACTIVE/EXECUTING → CANCELLED (`USER_CANCELLED`). 기존 주문을 되돌렸다고 표시하지 않음 |
| `GET` | `/api/v1/tasks/{taskId}/events?after=0&limit=50` | `MANDATE_DRAFTED`, `QUOTES_COLLECTED`, `POLICY_DECIDED`, `APPROVAL_REQUESTED`, `MANDATE_CONFIRMED`, `ORDER_CREATED`, `MANDATE_REVISED`, `TASK_STATUS_CHANGED` |

## 5. 상태 전이 / State transitions

개발 문서 표 + 두 가지 추가 (★):

| 현재 | 다음 | 트리거 |
|---|---|---|
| `AWAITING_APPROVAL` | `ACTIVE` | `/mandate/confirm` 검증 통과 |
| `AWAITING_APPROVAL` | `DECLINED` | 사용자 거절(`USER_REJECTED`) ★또는 후보 소진·시도 5회(`NO_VALID_CANDIDATE`) |
| `AWAITING_APPROVAL`, `ACTIVE` | `EXPIRED` | mandate 기한 경과 (조회·변경 시 적용) |
| `ACTIVE` | `EXECUTING` | 주문 생성 |
| ★`ACTIVE` | `AWAITING_APPROVAL` | mandate 수정 → 새 버전 재승인 필요 |
| `ACTIVE`, `EXECUTING` | `CANCELLED` | `/cancel` |

- `DENY`는 attempt에만 기록되고 Task를 곧바로 끝내지 않습니다. 시도 5회 도달 또는 살아 있는 모든 견적이 DENY되면 `DECLINED` + `NO_VALID_CANDIDATE`.
- `COMPLETED`·`FAILED`로 가는 경로(지급 확정·이행 확인)는 지급 구현 후 연결합니다.
- 시도(attempt) 상태: `POLICY_ALLOWED` → `APPROVED` → `ORDERED`, 또는 `BLOCKED`, `SUPERSEDED`.

## 6. AI 연동 계약 / AI handoff (#18)

`GET /proposal-context` 응답은 `aiproposal.MerchantProposal.Context` + `quotes: List<MerchantProposal.Quote>`와 필드가 1:1입니다.

- `maximumTotalBaseUnits` = 남은 Task 한도, `deadline` = `requiredFulfillmentBy` = mandate `expiresAt`, `permittedPairs` = mandate `allowedRecipients`.
- `mandateState: "ACTIVE"` = 제안을 받을 수 있음(Task `AWAITING_APPROVAL`, mandate `DRAFT`, 미만료). 그 외에는 Task 상태 이름.
- 견적의 `recipient`는 판매자가 말한 payTo입니다. `AiMerchantProposal`의 `RECIPIENT_NOT_PERMITTED` 필터가 한 번 더 걸러냅니다 (pharmacy-c).
- AI 결과 `PROPOSED`의 `proposedQuote.quoteId`를 `POST /attempts {"quoteId", "proposedBy":"AI"}`로 보내면 서버 정책이 최종 판정합니다. #18 담당자의 확인이 남아 있습니다.

## 7. DB (Flyway `V4__tasks_mandates_quotes_orders.sql`)

`tasks`, `mandate_versions`, `merchant_quotes`, `execution_attempts`, `approval_nonces`, `mandate_approvals`, `merchant_orders`, `task_events`. 데이터 명세서 대비 차이:

- 판매자 레지스트리는 `merchants` 테이블 대신 설정(`MerchantRegistry`, env)으로 둡니다. 견적 스냅샷에 `registry_recipient_address`와 `quoted_pay_to_address`를 함께 저장합니다.
- 견적 유일성은 `UNIQUE(task_id, external_quote_id)` (Task별 스냅샷).
- 예산 예약은 별도 `budget_reservations` 대신 취소되지 않은 `merchant_orders` 금액 합계로 계산합니다.
- 기존 `/api/executions` 테이블은 바꾸지 않았습니다.

## 8. 검증 / Verification

| 범위 / scope | 결과 / result |
|---|---|
| `./mvnw -B verify` (PostgreSQL 16.4) | 165 tests, 0 failures |
| `TaskHttpIntegrationTest` | 성공 경로(생성→견적→DENY 2건→ALLOW→EIP-712→주문→같은 키 동일 주문), 모델 recipient 차단, 타 지갑·위조 서명, 버전 수정 후 이전 서명 거절, 시도 한도·만료·거절, `confirmed`·소수 금액 거절 |
| `PurchaseApprovalTest` | web3j 인코더가 EIP-712 명세 Mail 벡터와 일치, 서버 digest = web3j digest, v 0/1·27/28 복구, high-s·형식 오류 거절 |
| `TaskPolicyTest`, `PharmacySimulatorTest` | 정책 사유별 판정, 약국 3곳·결정적 ID |

한계 / Limits: 로컬 시뮬레이터와 로컬 테스트 지갑 서명만 검증했습니다. 실제 Kiln 호출(#18), Sepolia 지급, 판매자 이행, 브라우저 지갑(MetaMask/Magic) 서명은 별도 증거가 있을 때만 완료로 표시합니다.
Only the local simulator and local test-wallet signatures were verified. Real Kiln, Sepolia payment, merchant fulfillment and browser-wallet signing need their own evidence.
