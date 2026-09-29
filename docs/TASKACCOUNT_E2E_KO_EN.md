# F031 TaskAccount purchase handoff / TaskAccount 구매 인계

Status 2026-09-30 KST: PR41 is merged; controller public-Sepolia backend E2E passed55checks. See [actual chain evidence](F031_INDEPENDENT_SEPOLIA.md) and the [frontend integration handoff](FRONTEND_E2E_HANDOFF_KO_EN.md). Frontend-user and hosted acceptance remain separate. The original worker verification record below is historical; fulfillment remains simulated.

## Mode and authority / 모드와 권한

Set `FLOWW_TASKACCOUNT_ENABLED=true` only with a Sepolia RPC URL, `FLOWW_TASKACCOUNT_EXECUTOR_KEY` and `FLOWW_TASKACCOUNT_REPORTER_KEY`, their matching public `..._ADDRESS` values, the selected token config, and a real trusted pharmacy recipient. The server checks the two keys' addresses, rejects equal signer addresses, rejects the Task owner's wallet as either signer, checks chain ID `11155111`, and rejects the known placeholder payees. An HTTP RPC URL is accepted only on loopback; a remote RPC URL must use HTTPS. These values remain server-side; never put keys in browser variables or committed files. With chain mode disabled, the existing `/api/v1/tasks/.../approval` and `/mandate/confirm` legacy path remains available for its local workflow. With chain mode enabled, those two routes return `CHAIN_MODE_REQUIRED`, and legacy `PurchaseApproval` can never initiate a chain payment.

The user wallet is the constructor caller (`msg.sender == owner`) and performs account deployment, ERC-20 allowance and `fund()` transactions. The server executor signs and relays `approveMandate(bytes)` and `executePayment(bytes32,uint256)`; the distinct reporter signs `confirmFulfillment(bytes32,bytes32)`. One purchase signature does **not** mean one wallet transaction overall. On the existing MetaMask SIWE flow, the owner JWT scopes every route below; body fields cannot choose the Task owner, amount, recipient, token, or calldata.

## ReviewSnapshotV1 / 검토 스냅샷

The selected policy-ALLOW attempt is frozen before account deployment. `reviewSnapshotDigest = keccak256(abi.encode(...))` with exactly this field order and Solidity types:

| Field | ABI type | Normalization |
| --- | --- | --- |
| `taskId` | `bytes32` | `keccak256(UTF8("floww:task:" + lowercase UUID))` |
| `mandateId` | `bytes16` | 16 raw UUID bytes |
| `mandateVersion` | `uint256` | positive integer |
| `attemptId` | `bytes16` | 16 raw UUID bytes |
| `merchantId` | `string` | exact persisted UTF-8 merchant ID |
| `quoteId` | `string` | exact persisted UTF-8 external quote ID |
| `amountBaseUnits` | `uint256` | positive integer, exact selected quote total |
| `recipient` | `address` | trusted registry address, lowercase |
| `token` | `address` | current configured settlement token, lowercase |
| `quoteExpiresAt` | `uint64` | Unix seconds |

The review hash has no EIP-712 domain, nonce, account address, or signature. The account constructor `expiresAt` is the earlier of quote and mandate expiry; it cannot be edited after deployment. Mandate revision or quote expiry invalidates the attempt. An expired bound account requires a new Task/account, not a changed immutable value.

Independent ethers 6.16 vector: Task `11111111-2222-4333-8444-555555555555`, mandate `66666666-7777-4888-8999-aaaaaaaaaaaa`, version `3`, attempt `bbbbbbbb-cccc-4ddd-8eee-ffffffffffff`, pharmacy `pharmacy-a`, quote `qt_a_reference_20260930`, amount `23500000`, recipient `0x1234567890123456789012345678901234567890`, token `0x1390c8745eb49069afd3b89393997e3fa14614f5`, expiry `1790712000` → task bytes32 `0x4c3eed53f51c8771edb4396a4b6e1351dfe91217178808ca5a5a8f30ade7d8e2`, review digest `0xec4d825949723205fc69f22c87699d674a0bbbbb7ac3cabd813beabac584915a`.

The **only** purchase signature is the contract's EIP-712 `MandateApproval(address owner,bytes32 taskId,bytes32 reviewSnapshotDigest,address token,address recipient,address executor,address fulfillmentReporter,uint256 maxSpend,uint64 expiresAt,uint256 nonce)`. Domain: `FlowwTaskAccount`, version `1`, Sepolia chain ID `11155111`, verifying contract = bound account address. `maxSpend` is the exact selected quote total (`23500000` for A), and the server requires the execution amount to equal it. The contract itself enforces `0 < amount <= maxSpend`; the server enforces exact equality, current policy, single order, Task cumulative budget, and signed account binding. `paymentId` is derived from the attempt and is outside the approval signature.

## HTTP sequence / HTTP 순서

All paths use `/api/v1/tasks/{taskId}` and `Authorization: Bearer <owner JWT>`. Examples omit unrelated Task response fields. Empty POST bodies may be `{}`.

1. Existing Task and quote/AI route: `POST /api/v1/tasks` with an idempotency key, `POST /{taskId}/quotes`, then empty-body `POST /{taskId}/ai-proposal` if configured. Reuse the returned attempt; do not create it again. Use `POST /{taskId}/attempts` only for a separate explicit/manual proposal or DENY test. The AI result remains a recommendation; a policy-ALLOW (`POLICY_ALLOWED`) attempt is required. B's `64000000` exceeds a `60000000` cap and C's quoted payee mismatches the trusted registry, so both record DENY and produce no account or signed transaction.
2. `POST /{taskId}/account/prepare` body `{"attemptId":"<UUID>","ownerAddress":"0x<owner wallet>"}`. Returns `state=PREPARED`, `chainTaskId`, `reviewSnapshotDigest`, exact amount/recipient/token/expiry, and `deploymentData`. Only the Task owner's linked wallet can be the constructor sender. One account row is reserved per Task, so another selected attempt cannot obtain concurrent spending authority.
3. The owner sends a contract-creation transaction with `data=deploymentData`, `to=null` on Sepolia. `POST /{taskId}/account/bind` body `{"accountAddress":"0x<deployed>","deploymentTxHash":"0x<tx hash>"}`. The server checks successful receipt and contract address, `from=owner`, `to=null`, exact pinned init bytecode plus constructor arguments, deployed runtime template, all immutable getters, and chain ID. A malicious contract with copied getters fails code/constructor checks.
4. `POST /{taskId}/account/approval-request` returns `typedData`, `digest`, `nonce`, `expiresAt`. The browser calls `eth_signTypedData_v4` with **that** typed data. `POST /{taskId}/account/signature` body `{"signature":"0x<65-byte signature>"}` verifies the recovered Task-owner wallet, on-chain digest/nonce, current policy and frozen review; Task becomes ACTIVE. Legacy approval is not reused.
5. `POST /{taskId}/account/approve` reserves the exact signed executor transaction and nonce in V5, sends it, and returns `APPROVAL_UNKNOWN` until `POST /{taskId}/account/reconcile` verifies receipt status and `MandateApproved` fields. `GET /{taskId}/account/funding` returns current account token balance and wallet calldata for token `approve(account, amount)` and account `fund(amount)`. The user wallet submits those transactions; funding is not inferred from a button click.
6. Existing `POST /{taskId}/orders` body `{"attemptId":"<UUID>"}` with `Idempotency-Key` reserves the selected quote amount and moves Task to EXECUTING. `POST /{taskId}/account/payment` rechecks current mandate/registry/token/quote/amount/budget, account code/getters/approval digest/nonce/active state and funding. It stores the stable `paymentId`, signed raw transaction and hash before sending. It returns `PAYMENT_UNKNOWN` until `POST /{taskId}/account/reconcile` verifies receipt status, exact `PaymentExecuted` fields, and token `Transfer` account→recipient of exact amount. A repeated payment request never allocates a second transaction. A dropped send response stays UNKNOWN and must be reconciled.
7. `POST /{taskId}/account/fulfillment` only after PAID invokes the **simulated** pharmacy result and stores its deterministic evidence hash, then relays reporter `confirmFulfillment(paymentId,evidenceHash)`. Reconcile verifies `FulfillmentConfirmed` event before marking account and Task COMPLETED. This is simulator evidence, not a real medicine delivery.

`GET /{taskId}/account` returns persisted account, approval, payment and fulfillment transaction hashes and operation states; `fulfillmentEvidenceMode=local_pharmacy_simulator` labels the simulated result. Existing Task and event routes show the Task lifecycle and order payment status. Signer operations remain `UNKNOWN` on RPC errors; server does not generate a new nonce/payment on timeout. A reverted or mismatched receipt is never marked PAID/COMPLETED and requires operator reconciliation. Wallet funding cannot be refunded by this API; the owner may use the contract's `refund()` only when the contract permits it.

## Verification boundary / 검증 범위

`./mvnw -o -B clean verify` on Java 21 with isolated PostgreSQL 16.4 passed 192 tests on the final working tree, including HTTP/JWT/V5/loopback RPC and independent ethers vectors. Loopback RPC is a fixture, not a Sepolia receipt. The controller separately verified 42 checks against a frozen earlier code SHA with actual local EVM and real Kiln; that result must not be silently attributed to a changed SHA. The controller owns any rerun, public Sepolia/MetaMask evidence, frontend behavior, domain-owner review and release. No server secret, public-chain transaction or publication was made by this worker.
