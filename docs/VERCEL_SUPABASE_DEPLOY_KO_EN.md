# Vercel + Supabase deployment preparation / 배포 준비

Status: **personal Supabase V1–V4 and protected Vercel Preview verified** (2026-09-30). [Preview](https://floww-server-demo-55jhm6jfc-geond.vercel.app) is Vercel-auth protected, not a public or production demo. The DevOps proposal in Confluence (page `14417921`, version 1, checked 2026-09-29) still recommends Render and explicitly requires a hosting decision; this technical verification does not silently change that team decision. See the [live verification record](../reports/VERCEL_PREVIEW_VERIFICATION_20260930.md).

## 한국어

### 구성과 확인된 범위

- `web5five/Floww_Server`의 루트 `Dockerfile.vercel`을 **백엔드 서비스 하나**로 빌드한다. 프런트엔드 저장소의 Vercel 프로젝트는 별도로 결정한다. A/B/C 약국마다 백엔드나 DB를 만들지 않는다. 최신 Task API에는 같은 서버 안의 결정적 3개 약국 시뮬레이터가 있지만 수취 주소는 placeholder이고 주문의 `paymentStatus`는 `NOT_ATTEMPTED`다. 레거시 실행 경로의 테스트 상점 어댑터는 여전히 loopback 전용이다. 실제 약국·결제·이행을 뜻하지 않는다.
- 컨테이너는 Java 21로 소스에서 JAR를 빌드하며 런타임에는 비root 사용자로 실행한다. `vercel` Spring 프로필은 `0.0.0.0:${PORT:8080}`에 바인딩한다. Vercel 프로젝트 환경변수에도 `PORT=8080`을 설정해야 라우터와 앱 포트가 일치한다. `EXPOSE`와 Dockerfile의 `ENV`만으로 Vercel 라우터 설정을 입증할 수 없다.
- DB, 실행·증거·지갑 nonce 등 영속 데이터는 PostgreSQL에 있다. 현재 요청 경로에서 스케줄러/백그라운드 워커는 발견되지 않았다. 그러나 실행을 `RUNNING`으로 기록한 뒤 컨테이너가 종료되면 `CREATED`만 재청구하는 코드 때문에 자동 복구가 불가능하다. **실행 중단·타임아웃 시 재시도/종결 정책은 코어 백엔드 담당자 확인 전까지 배포 수용 조건으로 남긴다.** 로컬 파일이나 컨테이너 메모리에 영속 상태를 두지 않는다.
- `main`의 인증 통합 이후 `vercel` 프로필에서는 `JwtAuthFilter`가 발급된 JWT를 업무 API에 검증하며 개발용 Alice/Bob 토큰은 `dev` 프로필 전용이다. 공개 배포에는 최소 32바이트의 안정적인 서버 전용 `JWT_SIGNING_KEY`가 필요하다. `FLOWW_CORS_ALLOWED_ORIGINS`는 지정한 정확한 HTTPS 출처만 허용하고 기본값은 빈 목록이다. 로컬 테스트 토큰을 공개 프런트엔드에 넣거나 `*` CORS를 사용하지 않는다.
- Supabase에는 사용자의 개인 Free `Floww` 조직 아래 **`Floww-demo` 프로젝트 한 개**를 서울 리전에 만들었다([대시보드](https://supabase.com/dashboard/project/vpmifrlwrbtfwgacojvk)). Supabase가 요구하는 조직 컨테이너의 유형이 `Personal`이며, 팀 조직·별도 A/B/C 프로젝트를 만들거나 유료 플랜으로 전환하지 않았다. Data API는 꺼 두고 DB 연결의 SSL 강제를 켰다. 실제 DB는 PostgreSQL 17.6이며 Flyway V1–V4가 성공했다. 계정 소유자는 개인이므로 팀 접근·장기 소유권은 별도 결정이 필요하다.
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
| `JWT_SIGNING_KEY`, `KILN_API_KEY` | 해당 기능의 서버 전용 값. Preview에만 Sensitive로 설정했다. JWT 키는 재기동·인스턴스 간 동일해야 한다. 브라우저 `NEXT_PUBLIC_*` 등으로 노출 금지 |
| `FLOWW_CORS_ALLOWED_ORIGINS` | 프런트엔드가 확정된 뒤 정확한 HTTPS origin을 쉼표로 지정. 기본값 빈 목록이며 wildcard·경로·공개 HTTP는 거부. 로컬 개발의 `http://localhost:<port>`만 예외 |
| `FLOWW_TASK_TOKEN_ADDRESS` | Preview 런타임에 새 fUSDC `0x1390c8745Eb49069afD3b89393997e3FA14614f5`를 명시 설정했다. 실제 Task 생성 응답에서 chain `11155111`, 해당 주소, decimals `6`을 확인했다. 소스 기본값은 이전 주소이며 이 확인은 체인 서명·지급 호환 증거가 아님 |
| `FLOWW_PHARMACY_A_RECIPIENT` 등 | 각 약국의 검증된 수취 주소가 나오기 전까지 소스의 placeholder를 실제 지급 주소로 취급하지 않음. TaskAccount의 단일 `recipient()`를 세 약국 모두에 매핑하지 않음 |
| `FLOWW_DEV_TOKEN_ALICE`, `FLOWW_DEV_TOKEN_BOB` | **`dev` 프로필의 격리된 로컬 테스트 전용**. `vercel` 프로필에 설정하지 않음 |
| 지갑·테스트 상점 변수 | `FLOWW_WALLET_*`, `FLOWW_TEST_MERCHANT_BASE_URL`은 각 기능 담당자가 승인한 경우에만 설정. 현재 테스트 상점 URL은 loopback만 허용 |

CA 값이 없으면 `verify-full` 연결은 실패하도록 두고, 검증을 끄거나 `sslmode=require`로 낮춰 통과시키지 않는다. Docker 빌드 인자·이미지·Git·문서에 암호/키/인증서를 넣지 않는다. Supabase Data API가 필요 없으면 노출을 꺼 두고, DB 접속은 서버에서만 한다.

### 배포 전 게이트

1. Vercel CLI의 현재 계정은 개인 `geond` Hobby다. `floww-server-demo` 프로젝트를 `Container` 프리셋으로 만들고 로컬 소스 CLI Preview를 배포했다. 공식 문서상 Hobby 프로젝트에는 조직 소유 Git 저장소를 직접 연결할 수 없으므로 Git 자동 배포는 없다. 유료 전환·접근권한 변경은 하지 않았다. Preview에는 Vercel 로그인 보호가 있고 공개 Production 배포는 없다.
2. 개인 Supabase DB의 Flyway V1–V4, TLS `verify-full`, `/actuator/health`, 이메일 JWT 및 Task 생성·조회 API를 확인했다. 다중 콜드 스타트에서 Flyway 잠금/연결 한도와 중단된 레거시 `RUNNING` 실행은 아직 확인해야 한다. 개인 프로젝트의 백업·복원 및 팀 인계 계획도 결정한다.
3. 실제 Preview에서 `$PORT` 바인딩, TLS Supabase health, 이메일 USER JWT Task 생성·조회, AI 제안 1회, 기본 거부 CORS와 인증 거부를 확인했다. 장시간 유휴 후 반복 콜드 스타트·동시 요청의 DB 연결 상한, JWT 소유자 격리의 클라우드 재검증, 지갑 로그인, 확정된 프런트엔드 origin의 CORS/URL 연결은 남았다. Health 200만으로 결제 준비를 주장하지 않는다.
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

### 추가 검증 / Follow-up (2026-09-30 KST)

- 기준: 리아님 Task API PR #36이 병합된 `main` `89d08c6`을 기존 PR #27 브랜치에 반영했다. README 충돌은 두 안내 링크를 모두 보존해 해결했다. 컨트롤러의 별도 AI 통합 체크아웃은 수정하지 않았다.
- 공유 DB 사전 조회: Flyway V1–V3 성공, `users=0`, 레거시 `executions=0`, V4 Task 테이블 0개. V4는 기존 테이블을 변경하지 않는 추가 마이그레이션으로 검토했다. 적용 뒤 Flyway V1–V4가 모두 성공하고 8개 Task 테이블이 존재하며 기존 두 테이블의 행 수는 0으로 유지됐다. DB reset/truncate는 하지 않았다.
- 실제 DB의 로컬 JAR에서 `PORT=8091` health `UP`, 이메일 JWT로 `POST /api/v1/tasks` → `AWAITING_APPROVAL`/mandate `DRAFT` 및 `GET /api/v1/tasks/{id}`를 확인했다. 합성 사용자·Task·mandate·이벤트만 정확히 삭제하고 잔여 스모크 데이터 0건을 재조회했다. 이 결과는 주문·지급·이행 증거가 아니다.
- 격리 PostgreSQL 16.4에서 전체 `./mvnw -B verify` **168/168 통과**. 정확한 출처 allowlist가 JWT 필터보다 앞에서 `OPTIONS`를 처리하고 미허용 출처를 403으로 거부하는 HTTP 회귀 3개를 포함한다. `Dockerfile.vercel` 로컬 소스 빌드가 성공했고, 로컬 이미지의 `PORT=8092` health `UP`, 공유 DB Flyway V4 검증, 허용 출처 사전 요청 200을 확인했다.
- Vercel CLI `56.5.0`의 현재 범위는 기존 `GEOND` Hobby($0). 조직 소유 `web5five` Git 저장소의 자동 연결은 공식 Hobby 제한이므로 경로는 로컬 소스 CLI Preview다. 이 항목은 2026-09-30 오전 **프로젝트 생성 전** 스냅샷이며, 같은 날 이후 Preview가 실제 생성됐다(아래 기록).
- 별도 컨트롤러 RPC 증거(`TOKEN_FUNDED_20260930_RPC.json`, 2026-09-29T16:24:56Z)는 새 TaskAccount가 기존 서버 기본값과 **다른** fUSDC 토큰을 참조함을 기록한다. 이 스냅샷은 배포 변수 검토 근거이며 SmartContract ABI/EIP-712 일치, 결제·이행의 실증이 아니다. 약국별 수취 주소는 여전히 미확정이다.

### 실제 Preview 확인 / Live Preview verification (2026-09-30 KST)

- 개인 `geond` Hobby의 `floww-server-demo` 프로젝트(`prj_bmQDw5hTZQVe5V0vDj4MPXYaolRZ`)는 GitHub 조직 저장소 연결 없이 `Container` 프리셋과 CLI 소스 배포를 사용했다. 최초 프리셋 `Other`로 생성된 빈 정적 Production 배포 `dpl_DB2LW2TftbmbNNtn3oHBScWzwHSj`는 HTTP 404 확인 후 **정확한 ID로 제거**했다. 현재 Production alias는 `DEPLOYMENT_NOT_FOUND`(404)이며 공개 서비스가 아니다. 자동 생성된 로컬 `.env.local` OIDC 토큰 파일도 제거했다.
- 최종 검증 Preview는 [URL](https://floww-server-demo-55jhm6jfc-geond.vercel.app), 배포 `dpl_4sa292459pUamFoiovrnzG2LUoy3`, 소스 `fb17b19`(AI 통합 `main` `436925a` 포함), Vercel 이미지 `sha256:4c111cf5e4a6bd25602ecd9853e5b971a98c6554724052dc3bf1f029c211f110`이다. 외부 비인증 요청은 Vercel SSO로 302 이동하며 CLI의 승인된 보호 우회로만 API를 검사했다.
- Preview health `UP`; 인증 없는 Task GET 401; 미허용 origin OPTIONS 403; AI 경로의 미인증 POST 401과 `Cache-Control: no-store`. 격리 PostgreSQL 16.4에서 통합 코드 `./mvnw -B verify` **187/187** 및 로컬 컨테이너 빌드 통과, PR #27 CI 두 `verify` 작업 통과.
- 실제 Preview에서 합성 USER 이메일 JWT로 Task 생성 응답의 `chainId=11155111`, token `0x1390c8745eb49069afd3b89393997e3fa14614f5`, decimals `6`, 수취인 3개를 확인했다. 처음 2시간 마감은 약국 A 이행 약속과 경계가 같아 `NO_ELIGIBLE_QUOTE`였다. 7일 마감 재시도에서 Kiln `qwen3-32b`의 `PROPOSED`/약국 A 추천, 서버 정책 `ALLOW`, B `OVER_BUDGET`, C `RECIPIENT_NOT_PERMITTED`를 확인했다. Task/mandate는 승인 전 상태, 주문 0, 지급 `NOT_ATTEMPTED`. 두 합성 사용자와 연결된 Task/견적/시도/이벤트를 정확히 삭제했고 DB 재조회에서 users/tasks/quotes/attempts/events/orders 모두 0.
- 이 검증은 모델 제안과 로컬 약국 시뮬레이터의 정책 판정까지다. 체인 TaskAccount의 EIP-712 구조가 서버의 현재 `PurchaseApproval`과 다르고 약국별 실수취 주소도 확정되지 않아 승인 서명·온체인 지급·이행 검증은 하지 않았다. 공개 프런트엔드 CORS origin도 아직 없고 기본값은 거부다. 자세한 명령 경계·증거·미해결 항목은 [검증 기록](../reports/VERCEL_PREVIEW_VERIFICATION_20260930.md)에 있다.

## English

Prepare one `Floww_Server` backend container on Vercel, with Supabase PostgreSQL; decide the separate frontend project independently. Do not provision three pharmacy backends. `Dockerfile.vercel` builds a Java 21 JAR from source; the `vercel` Spring profile binds to `0.0.0.0:${PORT:8080}`. Set `PORT=8080` in Vercel project settings as well. Use server-only runtime secrets and a verified Supabase TLS CA; this environment verified the IPv4 Session pooler on 5432, not the unverified Transaction pooler on 6543. Cap each container's Hikari pool and check the actual project connection limit. Supabase Free projects can pause after a week of low activity; check status before a scheduled demo. Exact HTTPS CORS origins must be configured after the frontend URL is known; the default allows no browser origin.

The personally owned Free `Floww-demo` Supabase project has Data API disabled, incoming DB SSL enforced, and Flyway V1–V4 applied. The protected Vercel [Preview](https://floww-server-demo-55jhm6jfc-geond.vercel.app) ran the Java 21 container against the CA-verified Session pooler: health UP; JWT Task create/read returned Sepolia chain 11155111, the explicitly configured new fUSDC token and six decimals. One real Kiln `qwen3-32b` request proposed pharmacy A and deterministic policy returned ALLOW; no approval, order, payment, or fulfillment occurred. An isolated PostgreSQL run passed 187 tests and PR CI passed twice. Synthetic DB rows were removed. This is **protected Preview proof, not public production or payment proof**. Legacy interrupted `RUNNING` executions still lack automatic recovery; core owner must decide that policy. Repeat cold-start/concurrency, frontend CORS, wallet and EIP-712 chain alignment, backup, and rollback checks before public release. `GEOND` Hobby cannot Git-connect the `web5five` organization repository, so this deployment used CLI source upload. Pharmacy recipients remain placeholders.

References: [Vercel Docker deployment and port/scale-in contract](https://vercel.com/kb/guide/docker), [Vercel Hobby Git restrictions](https://vercel.com/docs/limits), [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase TLS verification](https://supabase.com/docs/guides/platform/ssl-enforcement), [Supabase Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing).
