# F031 independent Sepolia verification / 독립 검증

2026-09-30 KST. This records a locally hosted backend using real Kiln and **public Sepolia**. It does not certify the deployed website or a user-operated frontend flow.

## Frozen implementation / 검증 대상

- Server code: `e86e3592eeed512d4f41601a1a46108d45472a39`. Later report/evidence commits do not change runtime code.
- Executable JAR SHA-256: `cc58571cbe975afb7ad1335909b4a81b925c7d88925aaf4c1482c696cae7d7a2`.
- Contract source: `web5five/Floww_SmartContract@d4e6a7d7b7635634b8a59f7c87bba91d3b311f9d`, solc0.8.28 optimizer200. Existing contract design retained.
- Actual PostgreSQL16.4 with Flyway V1–V5, separate validation database, default JWT authentication. No dev-token bypass.
- Worker Java/PostgreSQL suite:192 tests,0 failures/errors/skips; controller read the test XML totals and reviewed the source. Controller independently executed55 public-chain/runtime checks.

## Observed path / 실제 확인한 흐름

Scripted test-owner wallet signature → server JWT → Task(cap60fUSDC) → three persisted pharmacy quotes → live Kiln qwen3-32b selects Pharmacy A → deterministic ALLOW → frozen review → owner deploys account → backend verifies creation/runtime/immutables → canonical MandateApproval signature → executor relays approval → owner allowance/fund23.5fUSDC → persisted order/payment intent → exact payment → backend process restart → reconciliation of the same payment hash → reporter confirmation → verified event → Task COMPLETED.

- Task: `5665a02a-2300-4fab-866f-02a60ae57ead`
- Account: [0x219e7bfB4C4788Fa2b35638957B53a6900bF0655](https://sepolia.etherscan.io/address/0x219e7bfB4C4788Fa2b35638957B53a6900bF0655)
- Token: `0x1390c8745Eb49069afD3b89393997e3FA14614f5`, decimals6; exact amount23,500,000baseunits.
- Payment: [0x2e3110192ca84dbcafb5d6a0e925dd40cdd5fc5c700d161261afa10979281708](https://sepolia.etherscan.io/tx/0x2e3110192ca84dbcafb5d6a0e925dd40cdd5fc5c700d161261afa10979281708)
- Fulfillment: [0x8b6af8b662b87b86896e7179483da8fe94b11c60d6494fd20c6fc198f669ba60](https://sepolia.etherscan.io/tx/0x8b6af8b662b87b86896e7179483da8fe94b11c60d6494fd20c6fc198f669ba60)

소유자·실행자·수령 확인자는 서로 다른 지갑입니다. 실제 공개 테스트넷 거래이며, 약국 주문·수령 내용은 시뮬레이터입니다. 사용자 프론트에서 지갑을 조작한 인수 검증은 별도입니다.

## Independent assertions / 독립 확인

| Boundary | Evidence |
|---|---|
| Task↔selected quote↔account | Exact task hash, ABI review digest, deployment input, runtime and all immutable getters checked. One account/Task. |
| Signature | Independent ethers outer digest equals backend and live contract; actual authorization nonce used. |
| Exact amount | Token Transfer account→configured Pharmacy A and PaymentExecuted both23,500,000; recipient balance delta equal. |
| Restart/retry | PAYMENT_UNKNOWN, stable paymentId/hash survive JVM restart. Retry does not allocate another executor nonce. Existing receipt reconciles to PAID. |
| Completion | Task remains EXECUTING after payment; only matching FulfillmentConfirmed permits COMPLETED. Repeat payment/fulfillment requests allocate no new nonce. |
| Pharmacy B |64,000,000>60,000,000 → BUDGET_EXCEEDED; null hash, no account or signed operation.|
| Pharmacy C |Unauthorized payTo → RECIPIENT_NOT_ALLOWED; null hash, no account or signed operation.|
| DENY broadcast |Public executor nonce unchanged across DENY requests; local instrumented RPC and HTTP tests additionally show zero broadcasts. Signing was not instrumented on the public runtime; durable signed-operation count was zero and both endpoints rejected before account creation/signing.|
| Encoding |Java test vector, independent ethers ABI encoder, and actually executed Solidity pure encoder produce448 identical bytes and digest`0xec4d825949723205fc69f22c87699d674a0bbbbb7ac3cabd813beabac584915a`. The helper is validation-only; no second contract authorization format.|

The initial independent harness expected operation state CONFIRMED; the API uses VERIFIED. The assertion was corrected, then the same Task/account/transactions resumed. This was a harness correction, not a hidden server change or second purchase. The55 checks include the successful continuation.

## Earlier and remaining scopes / 범위 구분

- Earlier42-check actual-local-EVM/live-Kiln run belongs to code`bc3a4c0` and JAR`2bab29a31bc51f8f2b8e4a6b05970f82425993e2cd48dbba3b64a6d994822176`; it included a deliberately dropped RPC send response. Do not relabel it as final-SHA public-chain evidence.
- Receipt verification currently accepts a successful included receipt; production finality/reorg policy is not certified here.
- Unknown/reverted/mismatched operations remain visible and fail closed. There is no automatic gas replacement/resubmission operator workflow.
- The frontend must use the new account sequence when `FLOWW_TASKACCOUNT_ENABLED=true`; legacy PurchaseApproval routes deliberately return CHAIN_MODE_REQUIRED in that mode. Default remains false for compatibility.
- Optional Magic live login, frontend user acceptance, and the deployed Preview with these runtime settings remain separate checks.
- Secrets remain in local protected runtime storage and are absent from these artifacts. Never place private keys or raw signed transactions in issues, chat, documents or Git.

## Team issue alignment / 팀 이슈 대조

[Server#40](https://github.com/web5five/Floww_Server/issues/40) overlaps this change: binding, deterministic IDs, snapshots, deployment verification, signature, exact amount, expiry, durable execution, and DENY evidence. [Contract#8](https://github.com/web5five/Floww_SmartContract/issues/8) is supported by the existing contract and cross-runtime/live-chain evidence; this PR does not edit that repository or declare all owner checkboxes closed.

Runtime names are `FLOWW_TASKACCOUNT_EXECUTOR_ADDRESS/KEY` and `FLOWW_TASKACCOUNT_REPORTER_ADDRESS/KEY`; map the shorthand names in the issue to these documented keys. Actual frontend, contract-owner and deployment work should consume this API/artifact rather than reimplementing the shared server files.

Machine evidence: [public Sepolia](evidence/f031/sepolia-e2e-result.json), [database](evidence/f031/sepolia-db-evidence.json), [Solidity vector](evidence/f031/review-snapshot-solidity-vector.json). Integration instructions: [TaskAccount API](TASKACCOUNT_E2E_KO_EN.md).
