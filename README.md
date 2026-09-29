# Floww Server

Java 21 / Spring Boot 3.5.16 / PostgreSQL 16.4 backend for a confirmed purchase-scope mandate, bounded Kiln `qwen3-32b` tool conversation, server-fetched test merchant quote, deterministic policy precheck, and owner-only persisted evidence. `REVIEWED` is a local precheck; no merchant purchase, wallet signing, transaction broadcast, or fulfillment is available.

Start with [docs/API_CONTRACT.md](docs/API_CONTRACT.md) for setup, exact API/tool/evidence contracts, and [docs/TECHNICAL_DEMO.md](docs/TECHNICAL_DEMO.md) for bilingual demonstration and ownership handoff. The Maven wrapper is pinned to 3.9.16. Before a clean Compose setup, explicitly `docker pull postgres:16.4-alpine`; the Compose service intentionally uses `pull_policy: never`. `scripts/evaluate.sh` runs deterministic PostgreSQL/HTTP-fixture checks and writes `target/f006-evaluation.json`. `scripts/smoke.py` checks the packaged app and owner-isolated persistence.

`/actuator/health` is process/DB health. Authenticated `/api/integrations/readiness` reports Kiln/test merchant configuration; neither is proof of provider reachability or payment readiness. Without a configured merchant, the run fails closed. A configured merchant URL is permitted only on loopback and is labeled `local_test_merchant`.

The current bearer-token identity and startup `schema.sql` are local handoff mechanisms. Core backend, merchant, wallet, frontend, and human domain owners must review their respective contracts before shared deployment. F002 prior work and AI coding assistance are disclosed in the demo guide; no PAIVERA implementation was copied.

F006 acceptance fixes recheck the stored quote and mandate after the final model call, require a terminal status for complete evidence, and expose `modelEvidenceMode` plus per-attempt usage completeness. Local fixture calls are `local_model_fixture`; only the exact official endpoint is classified `kiln`, and historical events without provenance remain unknown. The F004 live Kiln evidence predates these fixes.

F008 adds a stateless, proposal-only AI draft preflight with [candidate contract and examples](docs/AI_DRAFT_CONTRACT.md). Its `READY_FOR_REVIEW` result means a structurally complete **model proposal**, not user approval, semantic verification, legal eligibility or executable authority. Human review and separate backend authorization remain required; the module does not mutate approved mandates or expose a production route. `scripts/evaluate_ai_draft.sh` runs only local model fixtures.

F009 adds a pure [review confirmation binding component](docs/REVIEW_CONFIRMATION_BINDING.md) that compares the exact reviewed F008 draft and server context with a separately supplied trusted confirmation receipt. `CONFIRMATION_MATCHED` is a binding check only; it does not create an executable mandate, wallet authorization or payment.

F010 exposes a candidate authenticated development [AI draft HTTP route](docs/AI_DRAFT_HTTP.md) at `POST /api/ai/drafts`. It returns clarification questions or a descriptive proposal with separate model evidence. It does not persist a review receipt or create execution/payment authority; product auth and frontend integration remain pending.
