# Vercel + Supabase deployment preparation / 배포 준비

Status: **Supabase project provisioned; Vercel backend not deployed** (2026-09-29). This is a proposed replacement for Render, not evidence of a working public demo. The current DevOps proposal in Confluence (page `14417921`, version 1, checked 2026-09-29) still recommends Render and explicitly requires a hosting decision; this preparation does not silently change that team decision.

## 한국어

### 구성과 확인된 범위

- `web5five/Floww_Server`의 루트 `Dockerfile.vercel`을 **백엔드 서비스 하나**로 빌드한다. 프런트엔드 저장소의 Vercel 프로젝트는 별도로 결정한다. A/B/C 약국마다 백엔드나 DB를 만들지 않는다. 다중 상점 식별·연동은 비즈니스 구현으로 해결해야 하며, 현재 테스트 상점 어댑터는 loopback 전용이므로 실제 A/B/C 약국 연동이 준비됐다는 뜻은 아니다.
- 컨테이너는 Java 21로 소스에서 JAR를 빌드하며 런타임에는 비root 사용자로 실행한다. `vercel` Spring 프로필은 `0.0.0.0:${PORT:8080}`에 바인딩한다. Vercel 프로젝트 환경변수에도 `PORT=8080`을 설정해야 라우터와 앱 포트가 일치한다. `EXPOSE`와 Dockerfile의 `ENV`만으로 Vercel 라우터 설정을 입증할 수 없다.
- DB, 실행·증거·지갑 nonce 등 영속 데이터는 PostgreSQL에 있다. 현재 요청 경로에서 스케줄러/백그라운드 워커는 발견되지 않았다. 그러나 실행을 `RUNNING`으로 기록한 뒤 컨테이너가 종료되면 `CREATED`만 재청구하는 코드 때문에 자동 복구가 불가능하다. **실행 중단·타임아웃 시 재시도/종결 정책은 코어 백엔드 담당자 확인 전까지 배포 수용 조건으로 남긴다.** 로컬 파일이나 컨테이너 메모리에 영속 상태를 두지 않는다.
- `main`의 인증 통합 이후 `vercel` 프로필에서는 `JwtAuthFilter`가 발급된 JWT를 업무 API에 검증하며 개발용 Alice/Bob 토큰은 `dev` 프로필 전용이다. 공개 배포에는 최소 32바이트의 서버 전용 `JWT_SIGNING_KEY`가 필요하다. 로컬 테스트 토큰을 공개 프런트엔드에 넣지 않는다.
- Supabase에는 사용자의 개인 Free `Floww` 조직 아래 **`Floww-demo` 프로젝트 한 개**를 서울 리전에 만들었다([대시보드](https://supabase.com/dashboard/project/vpmifrlwrbtfwgacojvk)). Supabase가 요구하는 조직 컨테이너의 유형이 `Personal`이며, 팀 조직·별도 A/B/C 프로젝트를 만들거나 유료 플랜으로 전환하지 않았다. Data API는 꺼 두고 DB 연결의 SSL 강제를 켰다. 실제 DB는 PostgreSQL 17.6이며 Flyway V1–V3가 성공했다. 계정 소유자는 개인이므로 팀 접근·장기 소유권은 별도 결정이 필요하다.
- Vercel 컨테이너 Function은 유휴 시 0으로 축소된다. Supabase Free 프로젝트도 낮은 사용량이 7일간 이어지면 일시 중지될 수 있다. 첫 요청의 콜드 스타트, Spring/Flyway 초기화, 함수 시간·메모리 제한, 동시 인스턴스별 DB 연결 수와 시연 전 DB 활성 상태를 실제 Preview에서 측정·확인한다. 무료 플랜 적합성이나 비용은 계정·프로젝트의 실제 한도 확인 전까지 확정하지 않는다.

### 프로젝트 환경변수 (값은 저장소에 넣지 않음)

| 이름 | 설정 원칙 |
| --- | --- |
| `PORT` | `8080`; Vercel 프로젝트 설정에 명시하고 실제 라우팅 확인 |
| `FLOWW_DB_URL` | `jdbc:postgresql://<Dashboard Connect의 Session pooler 호스트>:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/floww-db-ca.crt` 형태. 이 환경에서 직접 IPv6 호스트 DNS 해석이 실패하여 IPv4 Session pooler(5432)를 TLS `verify-full`로 실측했다. Transaction pooler(6543)는 JDBC prepared statement·세션 상태·Flyway 조합에서 검증하지 않았으므로 사용하지 않음 |
| `FLOWW_DB_USER` | 이 프로젝트의 Session pooler는 Dashboard Connect의 `postgres.<project-ref>` 사용자명. 직접 연결 시에는 `postgres` |
| `FLOWW_DB_PASSWORD` | Supabase DB 암호; Vercel 서버 런타임 Secret만 |
| `FLOWW_DB_CA_CERT_B64` | Supabase Dashboard의 Database SSL Configuration에서 받은 루트 CA 파일의 base64. 시작 시 `/tmp/floww-db-ca.crt`에 권한 0600으로 쓰고 원본 환경변수는 Java 프로세스에 전달하지 않음 |
| `FLOWW_DB_POOL_MAX` | 기본 `2` (인스턴스별). 실제 Supabase 연결 한도와 Vercel 동시 인스턴스 수 확인 후 조정 |
| `JWT_SIGNING_KEY`, `KILN_API_KEY` | 해당 기능을 켤 때 필요한 서버 전용 값. 브라우저 `NEXT_PUBLIC_*` 등으로 노출 금지 |
| `FLOWW_DEV_TOKEN_ALICE`, `FLOWW_DEV_TOKEN_BOB` | **`dev` 프로필의 격리된 로컬 테스트 전용**. `vercel` 프로필에 설정하지 않음 |
| 지갑·테스트 상점 변수 | `FLOWW_WALLET_*`, `FLOWW_TEST_MERCHANT_BASE_URL`은 각 기능 담당자가 승인한 경우에만 설정. 현재 테스트 상점 URL은 loopback만 허용 |

CA 값이 없으면 `verify-full` 연결은 실패하도록 두고, 검증을 끄거나 `sslmode=require`로 낮춰 통과시키지 않는다. Docker 빌드 인자·이미지·Git·문서에 암호/키/인증서를 넣지 않는다. Supabase Data API가 필요 없으면 노출을 꺼 두고, DB 접속은 서버에서만 한다.

### 배포 전 게이트

1. Vercel 프로젝트의 소유자, Hobby 사용 조건·한도, Git 연동 시 자동 Preview/Production 배포 범위를 확인한다. 현재 `web5five` 조직 소유 Git 저장소는 Vercel Hobby의 Git 연동 제한에 걸릴 수 있으므로 CLI 배포 또는 팀 플랜의 실제 가능성을 확인한다. **Vercel 프로젝트는 아직 만들지 않았으며 공개 배포·유료 전환도 하지 않았다.**
2. 새 개인 Supabase DB의 Flyway V1–V3, TLS `verify-full`, `/actuator/health`, 이메일 JWT 가입·보호 API를 확인했다. 다중 콜드 스타트에서 Flyway 잠금/연결 한도와 중단된 `RUNNING` 작업은 아직 확인해야 한다. 개인 프로젝트의 백업·복원 및 팀 인계 계획도 결정한다.
3. 실제 Vercel Preview에서 `$PORT` 바인딩, 콜드 스타트, 함수 시간·메모리 제한, 재기동 후 DB 상태, JWT 소유자 격리, 지갑 프로필, 프런트엔드 CORS/URL 연결을 확인한다. Health 200만으로 Kiln·상점·결제 준비를 주장하지 않는다.
4. 별도 약국 배포 없이 한 서비스의 상점 식별 계약을 담당자가 확인한다. 실제 상점 연동 전에는 결제/이행 성공을 시연 결과로 표기하지 않는다.
5. 릴리스 전에 이전 Vercel 배포로 되돌리는 절차와 **DB 마이그레이션은 앱 롤백만으로 되돌아가지 않는다는 점**을 문서화한다.

### 로컬 확인 기록 (2026-09-29 KST)

```sh
docker build --file Dockerfile.vercel --tag floww-vercel-prep:local .
# 별도 임시 PostgreSQL에 연결해 PORT를 바꾼 컨테이너의 health와 Flyway를 확인한다.
```

최신 `main` (`329d6a3`) 통합 후 격리된 PostgreSQL 16.4에서 `./mvnw -B verify` **144개 테스트가 통과**했다. 여기에는 발급된 지갑 JWT로 `/api/v1/users/me`의 지갑 주소 반환을 확인하는 3개 통합 테스트가 포함된다. `Dockerfile.vercel`의 소스 기반 빌드도 성공했다.

새 Supabase DB에서 PostgreSQL 17.6으로 Flyway V1–V3의 `success=true`를 확인했다. 공식 Supabase CA와 `sslmode=verify-full`을 사용한 Session pooler(5432) 연결, `PORT=8091`의 로컬 JAR와 `PORT=8092`의 Vercel용 로컬 이미지에서 각각 health `UP` 및 이메일 JWT 가입·보호 프로필 API를 확인했다. 두 스모크 테스트 계정은 제거했고 재조회 시 0개였다. 이 확인은 **실제 Supabase 연결과 로컬 이미지 실행**의 증거이지 Vercel Preview/Production 배포, Magic OTP, 실제 약국·Kiln·Sepolia·결제·이행 증거가 아니다.

### 작업 기록 / Worklog

- 작업: [서버 PR #27](https://github.com/web5five/Floww_Server/pull/27) 배포 준비; 담당: 배포 준비 에이전트, 최종 검토: 컨트롤러 대기. 2026-09-29 KST.
- 기준: `web5five/Floww_Server`의 `main` `329d6a3`, 브랜치 `feature/vercel-supabase-deploy-prep`. 팀 아키텍처 페이지 `11927569` v11 및 DevOps 제안 `14417921` v1을 2026-09-29에 읽었다. 후자는 아직 Render 제안이며 Vercel 전환의 팀 승인 기록이 아니다.
- 구현: 개인 Free Supabase 프로젝트 1개, Data API off, DB SSL on, Flyway V1–V3 적용; Vercel용 Docker·프로필은 PR의 기존 준비물을 유지. 암호와 테스트 JWT는 Git·문서에 저장하지 않았다.
- 검증: 격리 PostgreSQL 16.4의 `./mvnw -B verify` 144/144; `docker build --file Dockerfile.vercel` 성공; Supabase PostgreSQL 17.6의 인증서 검증 JDBC 연결, Flyway, 로컬 JAR·이미지 health/JWT 스모크 성공. 실제 Vercel Preview·결제·이행은 미검증.
- 미결정: 중단된 `RUNNING` 실행의 처리 정책, 개인 소유 DB의 팀 인계·백업, Vercel Hobby의 조직 Git 저장소 연결 방식, 공개 배포 승인. 팀 공용 작업 기록은 `PENDING_SYNC`이며 이 문서의 게시만으로 승인이나 Confluence 동기화를 주장하지 않는다.

## English

Prepare one `Floww_Server` backend container on Vercel, with Supabase PostgreSQL; decide the separate frontend project independently. Do not provision three pharmacy backends. `Dockerfile.vercel` builds a Java 21 JAR from source; the `vercel` Spring profile binds to `0.0.0.0:${PORT:8080}`. Set `PORT=8080` in Vercel project settings as well. Use server-only runtime secrets and a verified Supabase TLS CA; prefer the Dashboard's direct IPv6 endpoint or the IPv4 Session pooler on 5432, not the unverified Transaction pooler on 6543. Cap each container's Hikari pool and check the actual project connection limit. Supabase Free projects can pause after a week of low activity; check status before a scheduled demo.

The personally owned Free `Floww-demo` Supabase project is healthy, with Data API disabled, incoming DB SSL enforced, and Flyway V1–V3 applied. A local Java 21 image connected through the verified-CA IPv4 Session pooler and passed health and JWT smoke checks; an isolated local PostgreSQL run passed 144 tests, including wallet-JWT profile coverage. This is **not Vercel deployment proof**. JWT auth now protects business routes outside `dev`, but an interrupted `RUNNING` execution still has no automatic recovery; the core owner must decide its interruption policy. Verify cold starts, request duration, memory, concurrent connections/Flyway, frontend integration, migration/backup, and rollback in an actual Preview before production. Confirm Vercel Hobby's Git-organization restriction for the `web5five` repository before choosing the deployment workflow. No live Magic OTP, real A/B/C merchant integration, Kiln, Sepolia payment, or fulfillment is claimed.

References: [Vercel Docker deployment and port/scale-in contract](https://vercel.com/kb/guide/docker), [Vercel Hobby Git restrictions](https://vercel.com/docs/limits), [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase TLS verification](https://supabase.com/docs/guides/platform/ssl-enforcement), [Supabase Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).
