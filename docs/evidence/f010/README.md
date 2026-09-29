# F010 verification / HTTP 초안 검증

F010 makes the F008 proposal component callable through `POST /api/ai/drafts`. This record describes component evidence, not a complete purchase or human acceptance. / 초안 API 검증이며 실제 구매·사용자 수락 증거가 아닙니다.

- Java 21 / PostgreSQL: `./mvnw -B -o verify` passed **60 tests**, zero failures/errors/skips, including five new embedded HTTP tests. No dependencies or schema changes.
- Independent controller ran the packaged JAR against a loopback model fixture: **15 checks passed**, two fixture calls. Coverage includes anonymous/no-store, authority-field rejection, duplicate/trailing JSON, malformed UTF-8, real chunked oversize, clarification → review and separate truthful provenance.
- One real Kiln `qwen3-32b` call through the same packaged HTTP route returned **200 / READY_FOR_REVIEW**, one attempt, **1,170 reported tokens**. Provider cost was absent, so it remains unknown. The synthetic request concerned a public weather CSV; no purchase or wallet action occurred.
- The tested local JAR SHA256 is `bb18750f14caa8fc6f0f93338ac45739a9eb4ea8049bc370850f19eca0eaa47b`. [verification.json](verification.json) records source hashes, suites, fixture checks and live provenance. Rebuilt CI artifacts can have different packaging timestamps.

Orca GPT-6 Sol implemented the slice. Independent controller review corrected wildcard media-type acceptance, ensured mapped responses are non-cacheable, and isolated test bootstrapping from production discovery. A focused test bootstrap failure was corrected before the passing runs. The live probe initially required exact equality for a combined `no-store, no-store` header; parsing the valid directive list passed against the retained response without another model call or product change.

Orca 구현과 독립 컨트롤러 검수를 거쳤습니다. 집중 테스트 설정 오류와 검증 스크립트의 지나치게 엄격한 헤더 비교를 수정했으며, 제품의 승인·결제 제한을 완화하지 않았습니다. 실제 Magic 인증, 확인 기록의 영속화, 지갑 권한, 체인 거래 및 결과 수령은 미검증입니다.

See [HTTP contract and curl](../../AI_DRAFT_HTTP.md). The existing local startup remains in [API_CONTRACT.md](../../API_CONTRACT.md). Run `./mvnw -Dtest=AiDraftHttpIntegrationTest,AiDraftHttpNoProviderTest test` for isolated fixture checks; the full gate requires the documented PostgreSQL environment. A live call requires a separately configured server-side Kiln key and may consume credit.
