# F028 independent verification / 독립 검증

Checked 2026-09-30 KST. Combined implementation source: `9ca8e856d4a3afacca2f1599c9d78531a75d4724` (includes server main `511320d`). Subsequent report-only commits do not change tested Java code. Packaged JAR SHA-256: `492db905e436a9c2ee2c5fee5993b644a30c84dc736718cd444e7e933f8d8181`.

## Scope / 범위

Persisted owner-scoped Task → real Kiln qwen3-32b recommendation → deterministic policy attempt → separate synthetic EOA EIP-712 approval → idempotent simulated order. The server is local, PostgreSQL is a real isolated database in Docker, the model provider is the actual event Kiln. The signer is a generated test EOA; this is not a human-wallet or on-chain payment test.

저장된 Task → 실제 Kiln → 정책 시도 → 별도 테스트용 EOA 승인 → 시뮬레이션 주문까지 확인했습니다. 서버·DB는 로컬이고 모델은 실제 행사 Kiln입니다. 실제 사용자 지갑·체인 결제 검증이 아닙니다.

## Results / 결과

- Controller source review covered exact uint256 conversion, actual DRAFT state mapping, owner/role checks, body limits, no-store, immutable snapshot comparison, transaction boundaries, repeat handling and sanitized evidence. Shared auth/schema/approval/payment source files are unchanged by this PR.
- Java 21 + PostgreSQL 16.4, `./mvnw -o -B clean verify`: **184 tests, 0 failures, 0 errors, 0 skipped**, source unchanged during run.
- Real packaged HTTP flow: **21 checks passed**. Generated EOA wallet nonce/signature/JWT, persisted Task, three stored quotes, real Kiln tool call, A at `23500000`, B over-budget exclusion, C wrong-recipient exclusion, actual `POLICY_ALLOWED` attempt, and unchanged DRAFT mandate after AI.
- Separately submitted USER proposals B/C produce backend policy DENY. These are deterministic negative-path checks, not a claim that Kiln itself selected those rejected quotes. No signing/broadcast/payment occurs from the AI route.
- Independent Ethers digest matches the server's EIP-712 payload; test signature confirms the exact mandate; replay returns409; same order idempotency key returns the same order.
- Model evidence is linked to the Task event. Task `c58055cc-8760-45e2-9b76-365f8c2e2729`; tool call `call-6d1db8690b044e6f9018a16de68386ac`; provider generation `b430c5e5-c44a-46c6-bcb4-42a123d64aae`; reported tokens586 input /324 output /910 total; provider cost was not reported.
- First live invocation also returned a valid proposal and attempt. The harness initially rejected a semantically valid combined Cache-Control value (`no-store, no-store`) by requiring exact string equality. The harness was corrected to parse directives; the subsequent complete flow passed. No production guard was weakened. Two live provider calls total for this validation.
- Final Task state **EXECUTING**, payment **NOT_ATTEMPTED**, transaction hash absent. No payment/fulfillment/COMPLETED claim.

## Deployment and authority handoff / 배포·권한 인계

Validation explicitly configured new Sepolia fUSDC `0x1390c8745Eb49069afD3b89393997e3FA14614f5`, decimals6. Shared configuration defaults are owned separately; deployers must set the actual token explicitly. Merchant simulator recipients remain keyless fixtures, not the funded Task Account's real merchant mapping.

Contract source `aada98a` implements `MandateApproval` under domain `FlowwTaskAccount` with verifyingContract, bytes32 taskId, reviewSnapshotDigest and uint256 nonce. The current server implements `PurchaseApproval` under domain `Floww`, without verifyingContract, with string taskId/mandate/quote/version and bytes32 nonce. **These signatures are not interchangeable.** Ria Choi and Taeheon Choi must align the signed authority, task/snapshot mapping and execution adapter before payment. This AI change does not resolve that owner boundary.

Read-only Sepolia block11808760 confirms the supplied new account is approved and funded with60fUSDC. Reconstructed contract digest matches on-chain approvedDigest. Its taskId=1 and reviewSnapshotDigest=2 are demo configuration, not the persisted Task above. paymentExecuted=false and fulfillmentConfirmed=false.

한국어: 서버와 계약의 EIP-712 형식은 아직 달라 동일 서명을 그대로 전달할 수 없습니다. 리아님·태헌님의 서명 규격·Task/스냅샷 매핑·실행 연결 작업이 남았습니다. AI 통합 결과나 계정 입금을 전체 결제 E2E 완료로 해석하지 않습니다.

References: Confluence Challenge B page11960323 v3 and policy14516275 v3; server PR36/37; SmartContract PR6. Issue18 remains open for final integration acceptance and any remaining clarification/user-flow criteria. Confluence worklog: PENDING_SYNC; no external worklog update in this verification.
