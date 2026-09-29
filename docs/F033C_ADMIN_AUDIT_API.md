# F033C admin audit API / 관리자 감사 조회 API

Status: local implementation and local PostgreSQL verification; not a hosted admin acceptance result.

## Authority / 권한

All routes below require a valid existing JWT with `role=ADMIN` and `aud=admin` through the unchanged `JwtAuthFilter`. Anonymous requests return 401; client-audience tokens return 403. The separate Admin app signs in with `POST /api/v1/admin/auth/signin` and sets the resulting JWT in an HttpOnly cookie from its server route. Browser JavaScript cannot read it. There is no admin self-registration or payment action.

아래 경로는 기존 JWT 필터의 `ADMIN` 역할과 `admin` audience를 모두 요구합니다. 응답은 실제 PostgreSQL Task, attempt, event, TaskAccount 레코드의 허용된 필드만 사용합니다.

## GET routes / 조회 경로

| Route | Query | Response |
| --- | --- | --- |
| `/api/v1/admin/audit/tasks` | `status` (known Task status), `ownerId` (UUID), `page` (0–100000), `limit` (1–50, default 20) | `{tasks,total,page,limit}`; newest first by `(created_at,id)` |
| `/api/v1/admin/audit/tasks/{taskId}` | UUID path | `{task,attempts}` |
| `/api/v1/admin/audit/tasks/{taskId}/events` | `after` (nonnegative event sequence), `limit` (1–50, default 50) | `{events,nextCursor,hasMore}` |
| `/api/v1/admin/audit/tasks/{taskId}/account` | UUID path | Account summary or JSON `null` when the Task has no account |

Task rows include `taskId`, `ownerId`, primary `walletAddress` if present, goal/status/reason, current `mandateId`/version/status/expiry, item, cap in `maxAmountBaseUnits`, token address/decimals and timestamps. Attempt rows include `attemptId`, quote reference, selected merchant if a quote exists, status, policy decision/reason, amount in base units, recipient and timestamps. Event rows include only sequence, attempt ID, kind/state/reason, actor and timestamp. Account rows include task/attempt linkage, state, chain/account/owner/token/recipient, exact base-unit amount, selected public transaction hashes, operation states, payment/fulfillment identifiers and verification timestamps. A missing Task returns 404.

Amounts remain decimal integer strings in token base units. The Admin DTOs never include raw event payload JSON, typed data, signatures, raw signed transactions, JWTs, password hashes, private keys or internal configuration. Responses use `Cache-Control: no-store`; no POST/PUT/PATCH/DELETE handler exists in this namespace. The existing owner-scoped Task API and persistence schema are unchanged.

## Verification boundary / 검증 범위

The focused MockMvc test checks anonymous/client rejection, admin success, no-store, input limits, read-only routing and DTO exclusion. The database integration test uses a disposable local PostgreSQL 16 database and the real Spring HTTP/JWT boundary to check filters, pagination, persisted Task/attempt/account/event linkage and secret-marker exclusion. This synthetic fixture is not hosted Preview access, Sepolia proof or user acceptance.
# Independent controller verification — 2026-09-30

Controller reviewed the additive ADMIN-role and ADMIN-audience GET routes and explicit field projections, then reran the complete suite on the final code in a disposable native PostgreSQL 16 database: 197 tests, 0 failures, 0 errors, 0 skipped (Java 21, `./mvnw -q -DargLine=-Xmx512m test`). Earlier worker runs included one failure in the existing time-sensitive expiration test; both its focused rerun and this later independent full run passed. This proves local database/API regressions, not deployed admin access or a new live payment.
