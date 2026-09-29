# Floww Server F006 API and integration contract

For the complete current route inventory, response examples, and importable OpenAPI, see the [bilingual API handoff](API_HANDOFF_KO_EN.md) and [OpenAPI 3.0.3](openapi.json). This page focuses on the legacy F006 local quote-policy/evidence flow; `POST /api/ai/drafts` is a separate F010 proposal-only development route.

This Java 21/Spring Boot service persists an explicitly confirmed, owner-bound mandate, a loopback test-merchant quote, the Kiln tool conversation, and a **local policy precheck**. `REVIEWED` means a quote passed that precheck. No endpoint pays, signs, broadcasts, verifies fulfillment, or calls a real merchant. `TEST_USDC` is a test denomination; no price conversion or funded wallet is implied.

## Local run from a clean checkout

Use Java 21, Python 3, Docker Compose, and the pinned Maven wrapper. In terminal A, create an ignored `.env` from the example and generate local-only values without printing them. This initialization runs only if `.env` does not already exist; keep any existing local settings. The Compose file uses `pull_policy: never`, so explicitly pull the PostgreSQL image once on a clean machine.

```sh
cd Floww_Server
if [ ! -e .env ]; then
  cp .env.example .env
  python3 - <<'PYENV'
from pathlib import Path
import secrets
path = Path('.env')
text = path.read_text()
for placeholder in ('replace-with-a-local-only-password',
                    'replace-with-a-long-local-only-token',
                    'replace-with-another-long-local-only-token'):
    text = text.replace(placeholder, secrets.token_urlsafe(32))
path.write_text(text)
path.chmod(0o600)
PYENV
fi
set -a
. ./.env
set +a
docker pull postgres:16.4-alpine
docker compose --env-file .env up -d db
./mvnw -B verify
python3 scripts/merchant_fixture.py --port 18081
```

Keep terminal A's merchant fixture running. In **terminal B**, from `Floww_Server`, run the model HTTP fixture:

```sh
python3 scripts/kiln_fixture.py --port 18082
```

In **terminal C**, from `Floww_Server`, source the same ignored `.env` and start the application. The local-only fixture credential below is not a live Kiln key; the default application port is 8080, matching `smoke.py`.

```sh
set -a
. ./.env
set +a
export FLOWW_TEST_MERCHANT_BASE_URL=http://127.0.0.1:18081
export KILN_BASE_URL=http://127.0.0.1:18082/v1
export KILN_API_KEY=local-fixture-only-placeholder
java -Xmx512m -jar target/floww-server-0.1.0.jar
```

In **terminal D**, from `Floww_Server`, run `python3 scripts/smoke.py` and, if desired, `curl -s http://127.0.0.1:8080/actuator/health`. Stop only these three foreground fixture/app processes with Ctrl-C in their own terminals; leave the shared DB available to other workers. For a separate authorized live Kiln check, omit the fixture `KILN_BASE_URL`, supply the key through a protected process environment, and retain the merchant as `local_test_merchant`. Never copy a real key into `.env` or source control.

`/actuator/health` reports process/DB health only; authenticated `/api/integrations/readiness` reports whether the provider and test merchant are configured, never that they are reachable or payment ready. Host default binding is `127.0.0.1`; the Dockerfile sets `FLOWW_BIND_ADDRESS=0.0.0.0` and runs as UID 10001. Publish a container port only to a host loopback address.

## API

Every `/api` request requires `Authorization: Bearer <local development token>`. Tokens map to `alice` or `bob` on the server; request bodies cannot choose an owner. This temporary identity model needs core backend replacement before shared deployment.

| Method/path | Contract |
| --- | --- |
| `POST /api/executions` | `Idempotency-Key` and `{"confirmed":true,"mandate":{"goal":"...","itemId":"item-1","maxTotal":"10.00","currency":"TEST_USDC","recipient":"merchant_good","expiresAt":"<future ISO-8601>"}}`; same owner/key/body replays one ID, changed body is 409 |
| `POST /api/executions/{id}/run` | One claim, then bounded merchant/Kiln loop. A second claim is 409. |
| `GET /api/executions?limit=50` | Owner's newest executions as a JSON **array**, maximum 100; no cursor in this response. |
| `GET /api/executions/{id}` | Owner's persisted execution. |
| `GET /api/executions/history?limit=50&before=<execution-id>` | Stable `(created_at,id)` descending cursor, maximum 100; `nextCursor` is the last ID. |
| `GET /api/executions/{id}/events?after=0&limit=50` | Ordered `seq` cursor and `hasMore`, maximum 100. |
| `GET /api/executions/{id}/evidence.json?after=0&limit=100` | Owner-only `floww-evidence-2` export with event metadata and completeness flags. |
| `GET /api/integrations/readiness` | Configuration status without credentials. |

Use `scripts/smoke.py` for an expiry generated at runtime. Unknown/cross-owner IDs return 404; unauthenticated access returns 401. Monetary input and merchant total cost are exact positive **JSON decimal strings**, up to 12 integer and 8 fractional digits. No exponent, JSON numeric value, NaN, negative, extra fields, or model-supplied cost is accepted.

Evidence events contain `schemaVersion`, `actor`, `source`, `correlationId` (execution UUID), `toolCallId` when relevant, merchant `evidenceMode`, optional `modelEvidenceMode`, `createdAt`, `kind`, and a safe payload. Model-call and model-tool-request events use `source`/`modelEvidenceMode=local_model_fixture` for loopback HTTP, `kiln` only for the exact official `https://api.bricksum.com/v1` endpoint, and `unknown_model_provider` for other allowed HTTPS endpoints. The export has an aggregate `modelEvidenceMode`; historical model events without this field aggregate as `unknown` even if their old `actor` said `kiln`. This classification is based on configured endpoint identity, not model ID, and does not prove account ownership or live business execution. `modelUsage.status` is `complete` only if every recorded provider attempt has reported valid usage; otherwise it is `incomplete` and has no aggregate `totals`. Per-attempt usage remains visible. `after` refers to event `seq`. An export is `complete=true` only when requested from `after=0`, no more pages remain, and status is `REVIEWED`, `REJECTED`, or `FAILED`; `CREATED` and `RUNNING` are incomplete. `pageComplete` refers only to the current page. `progressLabel` is friendly copy separate from event payload. Existing F002 events retain schema version 1 and should be interpreted under the older local precheck contract. A client must follow `nextCursor` until `hasMore=false` for a paged export.

## Tool and trust boundaries

1. Mandate scope is one exact `itemId`, maximum full cost, exact recipient, currency, goal, and expiry. The model sees these nonsecret fields.
2. The only exposed tools are `search_offers({"itemId":"..."})`, `get_quote({"offerId":"..."})`, and `propose_purchase({"quoteId":"..."})`, in that order. Tool IDs must be unique. Unknown, malformed, repeated, unexpected, or early-stop calls fail closed.
3. `MerchantGateway` is server-owned. Its included adapter accepts only an explicitly configured **loopback HTTP test fixture**, follows no redirects, has short timeouts and bounded JSON. With no URL, it is unavailable. Offer ID must come from the search result. Quote fields come from the fixture and are stored in `execution_quotes` before proposal. The model may provide only a known quote ID, never price/recipient/expiry/URL.
4. Policy checks mandate and quote expiry, exact item, currency, recipient, and full quote cost within budget after quote retrieval, after proposal, and immediately before terminal `REVIEWED` using the stored execution-owned quote ID. The database transition to `REVIEWED` also checks the stored quote, mandate and overall deadline against current wall time in the same transaction. A stale quote or mandate at the final model response is `REJECTED`; elapsed overall run time is `FAILED`. Violations persist a `REJECTED` event; provider/merchant failure persists `FAILED`. This check does not reserve funds or authorize settlement.
5. Kiln model is fixed to `qwen3-32b`, with at most 4 logical calls, 3 tool calls, 768 output tokens per call, a 120-second loop deadline, and 30-second HTTP timeout per call. Transient 429/502/503/504 may retry once with bounded delay; 401/402/403 do not. Exact accepted assistant tool call and matching tool-result message are round-tripped. Hidden reasoning is never persisted. Per-call usage is reported or `unknown`; cost is an optional provider-supplied exact string, not estimated. Generation IDs and safe tool arguments/results are persisted.
6. `PaymentGateway` and `AuthoritativeFactPort` have **no implementations or public callbacks**. The future core/wallet owner must authenticate and verify a source fact, bind it to the execution and quote, then deduplicate on `(source_system,source_fact_id)` before changing status. The included database constraint provides that key; this module cannot create a success fact.

`REVIEWED` progress copy is “Quote ready for human review,” never purchase complete. The frontend should show fixture provenance, budget and rejection reasons, and lack of payment evidence. A process crash after `RUN_STARTED` may leave a `RUNNING` row; core owner must design durable recovery and atomic state transitions. The provisional `schema.sql` is startup initialization, not a production migration strategy.

## Reproducible evaluation

Run `scripts/evaluate.sh` with local PostgreSQL and database variables exported. It executes Java integration/unit tests against real PostgreSQL, a fake Kiln HTTP server, and a fake merchant HTTP server, then writes sanitized `target/f006-evaluation.json`. The matrix includes normal quote round-trip, budget, recipient/item/expiry, final-response expiry, evidence completeness, model provenance, incomplete retry usage, malformed/unknown/truncated/duplicate/loop, auth/owner, idempotency, and absent merchant. It does not include a live Kiln call or real merchant/payment proof. A live run must be labeled separately with actual model, usage, generation ID, and provider status.
