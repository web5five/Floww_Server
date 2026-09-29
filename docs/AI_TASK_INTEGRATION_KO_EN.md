# F028 AI Task integration / AI Task 연동

Status: local candidate for issue #18. The controller owns integration review, publication, and live Kiln evidence.

## Route / 경로

`POST /api/v1/tasks/{taskId}/ai-proposal` accepts an **empty body** and a client JWT for the Task's `USER` owner. The owner comes from the verified request attribute. A nonempty body, including chunked input, returns `400 INVALID_INPUT`; missing authentication and another owner's Task are rejected by the existing auth and Task boundaries. Responses use `Cache-Control: no-store`, including rejected requests.

`POST /api/v1/tasks/{taskId}/ai-proposal`은 **빈 본문**과 Task 소유자의 `USER` 클라이언트 JWT를 받습니다. 소유자는 검증된 요청 속성에서만 가져옵니다. chunked 입력을 포함한 본문이 있으면 `400 INVALID_INPUT`입니다. 기존 인증·Task 경계가 미인증 및 타인 Task 접근을 거절합니다. 거절 응답에도 `Cache-Control: no-store`가 적용됩니다.

The response has `proposal` (the existing `MerchantProposal.Result`), `attempt` (the existing Task `AttemptView`, or `null`), and `reusedAttempt` (boolean). `PROPOSED` is a model recommendation; only `attempt.policy.decision=ALLOW` records that deterministic policy allowed an attempt. The mandate remains `DRAFT`, the Task remains `AWAITING_APPROVAL`, and there is no approval, order, payment, or fulfillment from this route.

응답은 `proposal`(기존 `MerchantProposal.Result`), `attempt`(기존 Task `AttemptView` 또는 `null`), `reusedAttempt`(불리언)로 구성됩니다. `PROPOSED`는 모델 추천이며, 결정적 정책의 허용 결과는 `attempt.policy.decision=ALLOW`로 별도로 나타납니다. 이 경로는 위임을 `DRAFT`, Task를 `AWAITING_APPROVAL`로 유지하며 승인·주문·지급·이행을 수행하지 않습니다.

## Source mapping / 원천 매핑

The initial short transaction uses the owner scoped Task row lock, collects simulator quotes only for an eligible Task with no stored live quotes, and reads persisted Task, current mandate, remaining budget, and live quote rows. The actual mandate status is `DRAFT`; the older `/proposal-context` projection's `ACTIVE` means "proposable" and is **not** used as approval. The AI context carries the Task UUID, mandate UUID and version, item ID, exact base unit remaining budget, chain ID, token address and decimals, mandate expiry, and mandate's trusted merchant and recipient pairs. Each AI quote carries the persisted external quote ID, merchant ID, seller `quotedPayToAddress`, item, asset, exact total base units, stock, expiry, and fulfillment time. The persisted simulator has no prescription or identity requirement flags, so both map to false.

첫 짧은 트랜잭션은 소유자 범위 Task 행 잠금을 사용합니다. 저장된 유효 견적이 없는 적격 Task에서만 시뮬레이터 견적을 수집하고 Task·현재 위임·잔여 예산·유효 견적을 읽습니다. 실제 위임 상태 `DRAFT`를 사용합니다. 기존 `/proposal-context`의 `ACTIVE`는 "제안 가능"을 뜻하는 투영 값이며 승인으로 해석하지 않습니다. AI 입력에는 Task/위임 ID와 버전, 상품 ID, base unit 잔여 예산, 체인/토큰/소수점, 만료 시각, 위임에 저장된 판매자·수취인 쌍을 전달합니다. 견적에는 저장된 외부 ID, 판매자 ID, 판매자 제시 `quotedPayToAddress`, 상품, 자산, 정확한 총액, 재고, 만료 및 이행 시각을 전달합니다. 저장된 시뮬레이터 견적에는 처방·신원 요구 플래그가 없어 두 값은 false입니다.

The AI filter rejects over budget B and wrong recipient C before the model sees eligible quote data. Kiln receives eligible A only. The model call runs outside a database transaction. A second short transaction locks the same Task row, compares the full Task, mandate, quote rows, remaining budget and mapped context, then rechecks expiry. Only then is the chosen quote sent through `TaskService.propose`, which performs the existing policy checks. A changed revision, quote fact, quote set, budget, status, or expiry during inference returns `REJECTED` with `SNAPSHOT_STALE` or `SNAPSHOT_EXPIRED` and creates no attempt.

AI 필터는 예산 초과 B와 수취인 불일치 C를 모델 호출 전에 제외합니다. Kiln에는 적격 A 견적만 전달합니다. 모델 호출 중에는 DB 트랜잭션을 유지하지 않습니다. 두 번째 짧은 트랜잭션에서 같은 Task 행을 잠그고 Task·위임·견적 행·잔여 예산·AI 입력 전체를 비교한 뒤 기한을 재확인합니다. 그 후에만 기존 `TaskService.propose`의 정책 검사로 선택 견적을 보냅니다. 추론 중 버전, 견적 내용·집합, 예산, 상태, 기한이 바뀌면 `SNAPSHOT_STALE` 또는 `SNAPSHOT_EXPIRED`로 거절하고 시도를 만들지 않습니다.

## Repeats and evidence / 반복과 증거

Each request may call the model again. If the current locked snapshot still matches and the same mandate/quote has an existing `AI` `POLICY_ALLOWED` attempt with matching amount and recipient, the route returns that attempt with `reusedAttempt=true`; no additional attempt consumes the five attempt limit. Blocked, superseded, approved, or ordered attempts are not reused. A successful fresh recommendation otherwise creates one actual policy attempt. Model failure, malformed output, ineligible selection, and no candidate return `attempt=null`.

요청을 반복하면 모델을 다시 호출할 수 있습니다. 잠근 최신 상태가 일치하고 같은 위임·견적의 `AI` `POLICY_ALLOWED` 시도에 금액·수취인까지 일치하면 `reusedAttempt=true`로 기존 시도를 반환하며 5회 시도 한도를 추가 사용하지 않습니다. 차단·무효화·승인·주문된 시도는 재사용하지 않습니다. 그 외의 적격 추천은 실제 정책 시도를 1건 생성합니다. 모델 실패·형식 오류·부적격 선택·후보 없음은 `attempt=null`입니다.

An `AI_PROPOSAL_RESULT` Task event records bounded status, reason, original Task/mandate references and revision, quote findings, selected quote ID, attempt ID, reuse flag, model ID, evidence mode, finish reason, tool call ID, generation ID, usage status/counts, cost, and provider attempt count. The original references bind a stale rejection to the model's input even if the current mandate changed. IDs and cost are syntax bounded. Prompt text, raw tool arguments, credentials, personal data, balances, approvals, and transactions are not stored in this event. `local_model_fixture` evidence from tests is distinct from a real Kiln call.

`AI_PROPOSAL_RESULT` Task 이벤트에는 상태·사유·원래 Task/위임 ID와 버전·견적별 제외 사유·선택 견적 ID·시도 ID·재사용 여부와 제한된 모델 ID·증거 모드·종료 사유·도구 호출 ID·생성 ID·사용량·비용·호출 횟수를 기록합니다. 최신 위임이 바뀌어도 원래 ID·버전으로 모델 입력을 추적할 수 있습니다. ID와 비용은 문법을 제한합니다. 프롬프트 원문·도구 인자 원문·인증정보·개인정보·잔액·승인·거래는 이 이벤트에 저장하지 않습니다. 테스트의 `local_model_fixture`는 실제 Kiln 호출 증거와 구분합니다.

## Verification boundary / 검증 경계

The local HTTP test uses a real USER JWT, PostgreSQL V4 Task APIs and a loopback Kiln response fixture. It covers A selection and policy ALLOW, B/C findings, wrong owner, missing auth, body rejection, malformed/provider/ineligible output, no candidate, repeat, revision during inference, and quote/mandate expiry during inference. It does not verify event Kiln reachability, testnet payment, merchant fulfillment, user acceptance, or the controller's frozen head verification.

로컬 HTTP 테스트는 실제 USER JWT, PostgreSQL V4 Task API, 루프백 Kiln 응답 fixture를 사용합니다. A 선택과 정책 ALLOW, B/C 제외 사유, 타인 접근·미인증·본문 거절, 모델 형식·제공자·부적격 응답, 후보 없음, 반복, 추론 중 위임 수정 및 견적·위임 만료를 확인합니다. 행사 Kiln 연결, 테스트넷 지급, 판매자 이행, 사용자 수용, 컨트롤러의 고정 SHA 검증은 아직 확인하지 않았습니다.
