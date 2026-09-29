# Vercel + Supabase deployment preparation / 배포 준비

Status: **local preparation only** (2026-09-29). No Vercel or Supabase project has been created or deployed by this change. This is a proposed replacement for Render, not evidence of a working public demo. The DevOps proposal in Confluence (page `14417921`, version 1) and the actual project settings still require owner review.

## 한국어

### 구성과 확인된 범위

- `web5five/Floww_Server`의 루트 `Dockerfile.vercel`을 **백엔드 서비스 하나**로 빌드한다. 프런트엔드 저장소의 Vercel 프로젝트는 별도로 결정한다. A/B/C 약국마다 백엔드나 DB를 만들지 않는다. 다중 상점 식별·연동은 비즈니스 구현으로 해결해야 하며, 현재 테스트 상점 어댑터는 loopback 전용이므로 실제 A/B/C 약국 연동이 준비됐다는 뜻은 아니다.
- 컨테이너는 Java 21로 소스에서 JAR를 빌드하며 런타임에는 비root 사용자로 실행한다. `vercel` Spring 프로필은 `0.0.0.0:${PORT:8080}`에 바인딩한다. Vercel 프로젝트 환경변수에도 `PORT=8080`을 설정해야 라우터와 앱 포트가 일치한다. `EXPOSE`와 Dockerfile의 `ENV`만으로 Vercel 라우터 설정을 입증할 수 없다.
- DB, 실행·증거·지갑 nonce 등 영속 데이터는 PostgreSQL에 있다. 현재 요청 경로에서 스케줄러/백그라운드 워커는 발견되지 않았다. 그러나 실행을 `RUNNING`으로 기록한 뒤 컨테이너가 종료되면 `CREATED`만 재청구하는 코드 때문에 자동 복구가 불가능하다. **실행 중단·타임아웃 시 재시도/종결 정책은 코어 백엔드 담당자 확인 전까지 배포 수용 조건으로 남긴다.** 로컬 파일이나 컨테이너 메모리에 영속 상태를 두지 않는다.
- 현재 `DevAuthFilter`는 시작 시 최소 하나의 개발용 Alice/Bob bearer token(16자 이상)을 강제하며 업무 API는 발급된 JWT가 아니라 이 개발용 토큰을 검사한다. 따라서 `JWT_SIGNING_KEY`만 넣어서는 서버가 시작하지 않고, JWT를 발급해도 업무 API에 사용할 수 없다. 임시 토큰을 공개 프런트엔드에 넣지 않는다. **공개 배포 전 공통 인증 연결을 담당자가 승인·구현해야 한다.**
- Vercel 컨테이너 Function은 유휴 시 0으로 축소된다. Supabase Free 프로젝트도 낮은 사용량이 7일간 이어지면 일시 중지될 수 있다. 첫 요청의 콜드 스타트, Spring/Flyway 초기화, 함수 시간·메모리 제한, 동시 인스턴스별 DB 연결 수와 시연 전 DB 활성 상태를 실제 Preview에서 측정·확인한다. 무료 플랜 적합성이나 비용은 계정·프로젝트의 실제 한도 확인 전까지 확정하지 않는다.

### 프로젝트 환경변수 (값은 저장소에 넣지 않음)

| 이름 | 설정 원칙 |
| --- | --- |
| `PORT` | `8080`; Vercel 프로젝트 설정에 명시하고 실제 라우팅 확인 |
| `FLOWW_DB_URL` | `jdbc:postgresql://<Dashboard Connect에서 복사한 호스트>:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/floww-db-ca.crt` 형태. IPv6 직접 연결이 안 되면 IPv4 Session pooler(5432)를 선택. Transaction pooler(6543)는 현재 JDBC prepared statement·세션 상태·Flyway 조합에서 검증하지 않았으므로 사용하지 않음 |
| `FLOWW_DB_USER` | 직접 연결 `postgres`, Session pooler는 Dashboard에 나온 `postgres.<project-ref>` 등 실제 사용자명 |
| `FLOWW_DB_PASSWORD` | Supabase DB 암호; Vercel 서버 런타임 Secret만 |
| `FLOWW_DB_CA_CERT_B64` | Supabase Dashboard의 Database SSL Configuration에서 받은 루트 CA 파일의 base64. 시작 시 `/tmp/floww-db-ca.crt`에 권한 0600으로 쓰고 원본 환경변수는 Java 프로세스에 전달하지 않음 |
| `FLOWW_DB_POOL_MAX` | 기본 `2` (인스턴스별). 실제 Supabase 연결 한도와 Vercel 동시 인스턴스 수 확인 후 조정 |
| `JWT_SIGNING_KEY`, `KILN_API_KEY` | 해당 기능을 켤 때 필요한 서버 전용 값. 브라우저 `NEXT_PUBLIC_*` 등으로 노출 금지 |
| `FLOWW_DEV_TOKEN_ALICE`, `FLOWW_DEV_TOKEN_BOB` | **격리된 로컬 테스트 전용**. 현 코드의 시작 조건 때문에 최소 하나가 필요하지만, 공개 Vercel 프로젝트에 임시 토큰을 배포하는 것으로 인증 게이트를 우회하지 않음 |
| 지갑·테스트 상점 변수 | `FLOWW_WALLET_*`, `FLOWW_TEST_MERCHANT_BASE_URL`은 각 기능 담당자가 승인한 경우에만 설정. 현재 테스트 상점 URL은 loopback만 허용 |

CA 값이 없으면 `verify-full` 연결은 실패하도록 두고, 검증을 끄거나 `sslmode=require`로 낮춰 통과시키지 않는다. Docker 빌드 인자·이미지·Git·문서에 암호/키/인증서를 넣지 않는다. Supabase Data API가 필요 없으면 노출을 꺼 두고, DB 접속은 서버에서만 한다.

### 배포 전 게이트

1. Vercel/Supabase 프로젝트 소유자, 플랜, 예상 사용량·비용, Git 연동 시 자동 Preview/Production 배포 범위를 확인한다. 승인 전 프로젝트 생성·유료 전환·공개 배포를 하지 않는다.
2. 새/격리된 DB에서 Flyway V1–V3와 `/actuator/health`를 확인한다. 기존 데이터가 있는 DB에 자동 마이그레이션을 처음 적용하기 전에는 백업·복원 계획 및 migration 검토가 필요하다. 다중 콜드 스타트에서 Flyway 잠금/연결 한도도 확인한다.
3. 공개 Preview 전에 개발용 bearer token 의존성을 제거하거나 승인된 제한 접근 방식으로 대체하고, 발급된 JWT가 실제 업무 API에서 검증되는지 확인한다. Preview의 실제 `$PORT` 바인딩, 콜드 스타트, 타임아웃/중단된 `RUNNING` 작업, 재기동 후 DB 상태, 인증 및 소유자 격리, 실제 프런트엔드 CORS/URL 연결을 확인한다. Health 200만으로 Kiln·상점·결제 준비를 주장하지 않는다.
4. 별도 약국 배포 없이 한 서비스의 상점 식별 계약을 담당자가 확인한다. 실제 상점 연동 전에는 결제/이행 성공을 시연 결과로 표기하지 않는다.
5. 릴리스 전에 이전 Vercel 배포로 되돌리는 절차와 **DB 마이그레이션은 앱 롤백만으로 되돌아가지 않는다는 점**을 문서화한다.

### 로컬 확인 기록 (2026-09-29 KST)

```sh
docker build --file Dockerfile.vercel --tag floww-vercel-prep:local .
# 별도 임시 PostgreSQL에 연결해 PORT를 바꾼 컨테이너의 health와 Flyway를 확인한다.
```

격리된 PostgreSQL 16.4에 대해 `./mvnw -B verify` 134개 테스트가 통과했다. Vercel 이미지의 소스 기반 로컬 빌드가 통과했고, `PORT=8091`로 실행한 컨테이너에서 `/actuator/health`가 `UP`, Flyway V1–V3 기록이 모두 성공이었으며 재시작 후에도 그대로였다. 최종 이미지에는 기존 지갑 로그인 예시 정적 파일도 포함되어 로컬 GET이 200을 반환했다. 처음에는 개발용 bearer token을 넣지 않아 서버 시작이 실패했고, 원인을 인증 게이트로 남긴 다음 **로컬 테스트 전용** 토큰을 주입해 재검증했다. 이 로컬 확인은 Vercel Preview/Production 또는 Supabase 접속을 증명하지 않는다.

## English

Prepare one `Floww_Server` backend container on Vercel, with Supabase PostgreSQL; decide the separate frontend project independently. Do not provision three pharmacy backends. `Dockerfile.vercel` builds a Java 21 JAR from source; the `vercel` Spring profile binds to `0.0.0.0:${PORT:8080}`. Set `PORT=8080` in Vercel project settings as well. Use server-only runtime secrets and a verified Supabase TLS CA; prefer the Dashboard's direct IPv6 endpoint or the IPv4 Session pooler on 5432, not the unverified Transaction pooler on 6543. Cap each container's Hikari pool and check the actual project connection limit. Supabase Free projects can pause after a week of low activity; check status before a scheduled demo.

This is not deployment proof. Although state is stored in PostgreSQL and no continuously running worker was found, an interrupted `RUNNING` execution currently has no automatic recovery. The core owner must decide its interruption policy. The current `DevAuthFilter` also requires a local-development bearer token at startup and does not accept newly issued JWTs for business routes; a signing key alone is insufficient. Do not ship demo tokens to a public frontend. Verify the approved auth integration, cold starts, request duration, memory, concurrent connections/Flyway, frontend integration, migration/backup, and rollback in an actual Preview before production. No real A/B/C merchant integration or payment/fulfillment is claimed.

References: [Vercel Docker deployment and port/scale-in contract](https://vercel.com/kb/guide/docker), [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase TLS verification](https://supabase.com/docs/guides/platform/ssl-enforcement), [Supabase Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).
