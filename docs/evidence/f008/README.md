# F008 verification / 검증 기록

Observed on 2026-09-29 KST. Scope: stateless AI draft clarification and structural preflight; [component issue #5](https://github.com/web5five/Floww_Server/issues/5). Implementation used supervised GPT-6 Sol in Orca; the controller independently reviewed source and added counterexamples. No human acceptance is claimed.

## Executed checks / 실제 검사

- Java 21, Maven Wrapper 3.9.16, PostgreSQL 16.4. `./mvnw -B -o -q verify` passed **42 tests**, zero failures/errors/skips: 13 existing server tests, 24 draft evaluation tests, 5 independent controller tests. A dedicated test database was used, not the shared application database.
- `scripts/evaluate_ai_draft.sh` passed the 23-case corpus; one additional test covers conversation bounds. The controller's final `verify` reran those cases together with independent deadline-instant/equality, missing-domain, exact-money/fees, privileged/oversized input, escaped duplicate-key and trailing-JSON checks. [Fixture report](fixture-evaluation.json) is local evidence, not live model performance evaluation.
- The existing PostgreSQL regression report retained all 22 mapped scenarios. No new runtime dependency, endpoint, migration, service, authentication or payment adapter was added.
- `git diff --check` and JSON syntax validation passed.

검증은 새 초안 기능과 기존 서버의 호환성을 확인합니다. 구조적으로 완성된 모델 제안이 사용자 승인이나 실행 권한을 의미하지 않습니다. 실제 사용자 관찰, Magic 연동 및 온체인 지급은 별도 통합 조건입니다.

## One live Kiln probe / 실제 모델 확인

[Sanitized receipt](live-proposal.json): normal Java adapter → official Kiln → `qwen3-32b` → `propose_ai_draft` → deterministic preflight returned `READY_FOR_REVIEW` on one synthetic public-data CSV scenario. One request attempt; 548 prompt + 517 completion = **1,065 reported tokens**. Provider cost was not supplied and remains unknown. No merchant, wallet, signing, payment or fulfillment was invoked.

실제 모델 호출 1건에서 도구 호출·사용량·검토용 초안 반환을 확인했습니다. 일반적인 정확도나 프롬프트 공격 방어율을 입증한 것이 아닙니다. 입력은 합성 시나리오이며 실제 사용자의 구매 요청이 아닙니다.

## Artifact and boundaries / 파일과 검증 범위

The local packaged JAR after the controller's final verification has SHA-256:

`e98502e0eae75aeb673eff9c0ba46857311e9daf9d4b4634d168ccaeed210c78`

The live probe ran against the same Java implementation before the final packaging and the addition of controller tests/documentation. The JAR hash identifies the local build, not the earlier live-call process or a reproducible CI binary.

Reproduce local scenarios with Java 21 via `scripts/evaluate_ai_draft.sh`; run full `./mvnw -B verify` with the PostgreSQL environment described in [API setup](../../API_CONTRACT.md). Existing public runtime behavior is unchanged. [Draft contract](../../AI_DRAFT_CONTRACT.md) is a candidate for frontend/backend agreement; there is no deployed AI draft HTTP route. Product choice, semantic grounding, identity/session integration, mandate confirmation and spending enforcement remain outside this component.
