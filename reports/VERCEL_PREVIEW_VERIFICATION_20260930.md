# Floww protected Vercel Preview verification — 2026-09-30 KST

## Identifiers and scope

- Supabase: personal Free `Floww` organization, `Floww-demo` project `vpmifrlwrbtfwgacojvk`, PostgreSQL 17.6 in Seoul. Data API off; incoming DB SSL enforced. Session pooler 5432 used with `sslmode=verify-full` and the Supabase CA.
- Vercel: personal `geond` Hobby project `floww-server-demo` (`prj_bmQDw5hTZQVe5V0vDj4MPXYaolRZ`), `Container` framework, no connected Git organization repository and no paid-plan or access-policy change.
- Verified Preview: https://floww-server-demo-55jhm6jfc-geond.vercel.app ; deployment `dpl_4sa292459pUamFoiovrnzG2LUoy3`; source commit `fb17b19b1aea17e7ce534a8610addfe9e5a292ef`, which includes merged AI integration main `436925ace74caf5d5cb22d8858aeb1ba33bf0f40`; Vercel OCI image `sha256:4c111cf5e4a6bd25602ecd9853e5b971a98c6554724052dc3bf1f029c211f110` (deployment build output). The Preview has Vercel Authentication; ordinary unauthenticated requests to `/actuator/health` returned SSO redirect 302. Authorized `vercel curl --deployment dpl_4sa292459pUamFoiovrnzG2LUoy3` supplied the protection bypass for testing; its token was never recorded here.
- An initial empty static Production deployment was inadvertently created under framework `Other` (`dpl_DB2LW2TftbmbNNtn3oHBScWzwHSj`), returned 404, and was removed by exact deployment ID. The Production alias subsequently returned `404 DEPLOYMENT_NOT_FOUND`. No working/public Production application was left behind. A CLI-generated, ignored local `.env.local` OIDC token file was removed after deployment.

## Checks observed on the verified Preview

| Check | Observed result |
| --- | --- |
| OCI startup and verified PostgreSQL connection | `GET /actuator/health` → 200 `{"status":"UP"}` after a cold start; Vercel image build and push succeeded. |
| Protection/authentication | Ordinary external health → SSO 302. Protected CLI request without app JWT `GET /api/v1/tasks` → 401 `UNAUTHORIZED`. |
| CORS default | Untrusted `Origin: https://untrusted.example` preflight to Task → 403 `Invalid CORS request`; no frontend origin has been allowlisted yet. |
| AI response caching boundary | Unauthenticated `POST /api/v1/tasks/00000000-0000-0000-0000-000000000000/ai-proposal` → 401 with `Cache-Control: no-store`. |
| Task asset and merchant identities | Synthetic USER email JWT created Task in `AWAITING_APPROVAL` with mandate `DRAFT`; `chainId=11155111`, `tokenAddress=0x1390c8745eb49069afd3b89393997e3fa14614f5`, `tokenDecimals=6`, and three allowed recipient identities. This address was explicitly configured as a Preview runtime variable; it is not the source default. |
| Model/policy | A first synthetic Task with a two-hour deadline produced `NO_CANDIDATE/NO_ELIGIBLE_QUOTE`, consistent with pharmacy A's two-hour promised fulfillment boundary. A second Task with a seven-day deadline reached real Kiln model `qwen3-32b` (`modelEvidenceMode=kiln`), returned `PROPOSED` for pharmacy A, and persisted one AI attempt with deterministic policy `ALLOW`. Pharmacy B was `OVER_BUDGET`; C was `RECIPIENT_NOT_PERMITTED`. |
| No purchase claim | Second Task remained `AWAITING_APPROVAL`, mandate `DRAFT`, attempt payment `NOT_ATTEMPTED`, order count 0. No user approval signature, chain transaction, settlement, or fulfillment was attempted. |
| Cleanup and schema | Both synthetic email users and their Task-linked rows were deleted by exact owner relation in transactions. Read-only database query after cleanup: Flyway `1:true,2:true,3:true,4:true`; users/tasks/quotes/attempts/events/orders each `0`. No reset, truncate, or unrelated-row deletion. |

The original local verification before this final Preview was Java 21 `./mvnw -B verify` against an isolated PostgreSQL 16.4 container: **187 tests, 0 failures/errors/skips**. The exact disposable regression container `floww-vercel-ai-regression-20260930` was removed after the test; it held no shared data. The merged-source local `Dockerfile.vercel` image built as `sha256:0cb6fe355ad070aa66c1fe54e38746fbf0078debd55e08df7364dd115fdfac87`. PR #27's two `verify` jobs on pushed `fb17b19` passed: [run 36600364784](https://github.com/web5five/Floww_Server/actions/runs/36600364784) and [run 36600375367](https://github.com/web5five/Floww_Server/actions/runs/36600375367).

## Boundaries before a public demo

- Preview is protected and uses a personal free account. The organization-owned private GitHub repository is not Git-connected to Hobby; future deploys require intentional CLI upload or an approved plan/account change. No production promotion was performed.
- `FLOWW_CORS_ALLOWED_ORIGINS` is unset/closed until the actual frontend HTTPS origin is approved. Wallet sign-in is off; Magic OTP is not verified.
- Server Task `PurchaseApproval` EIP-712 shape and the newly merged TaskAccount contract `MandateApproval` shape differ (domain and message fields). A policy ALLOW is **not** contract-compatible signing or payment authority. Pharmacy recipient addresses in the server are placeholders, not validated payees. Do not map the contract's single recipient to A/B/C.
- Request-driven AI work and PostgreSQL-backed Task state do not require a continuously running process. A legacy execution interrupted in `RUNNING` lacks automatic recovery; its owner must define fail/ retry/reconciliation policy before representing it as durable production work.
- Repeat cold-start, concurrent instance/Flyway connection, user-isolation, backup/restore, rollback, frontend/CORS, and end-to-end chain checks before an unprotected public demo. Supabase Free inactivity pause and Vercel Hobby limits remain operational risks.

No DB password, JWT, Kiln API key, protection bypass secret, wallet key, or raw provider payload is in this report.
