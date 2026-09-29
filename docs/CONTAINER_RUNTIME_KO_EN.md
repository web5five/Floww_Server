# F016 container runtime check / 컨테이너 실행 검증

## 한국어

`python3 scripts/container_smoke.py`는 Java 21과 고정된 Maven Wrapper로 JAR를 패키징하고, 기존 Dockerfile로 실제 애플리케이션 이미지를 빌드한 다음, 새 `postgres:16.4-alpine` 컨테이너에 연결해 검사합니다. Dockerfile, Compose, 기존 DB 및 데이터는 수정하지 않습니다. 이 스크립트의 성공은 **로컬 컨테이너 실행 검증**이며 Kiln 호출, 결제, 상품 인도 또는 사용자 승인을 증명하지 않습니다.

준비물: Python 3, Java 21 (`JAVA_HOME` 설정), 실행 가능한 Docker, 로컬에 캐시된 `postgres:16.4-alpine` 및 `eclipse-temurin:21-jre`, Maven 의존성(없으면 Maven이 필요한 의존성만 가져올 수 있음). 기본 이미지는 자동 pull하지 않습니다. 약 11 GiB의 여유 공간을 확인한 F016 환경을 대상으로 하며, Maven 힙은 512 MiB, PostgreSQL은 256 MiB/1 CPU, 앱은 640 MiB/1 CPU로 제한합니다.

```sh
export JAVA_HOME=/path/to/java-21
python3 scripts/container_smoke.py
```

검사 항목: JAR SHA-256, 새 DB의 Flyway V1 성공 기록 및 최초 마이그레이션 로그, `/actuator/health`, 인증된 실행 생성·조회·증거, 타 소유자 404 및 익명 401, 공급자 미설정 상태 표시, `MERCHANT_NOT_CONFIGURED`로 끝나는 명시적 `FAILED` 이벤트, 앱 재시작 후 동일 기록과 단일 Flyway V1 기록입니다. 누락된 공급자 검사에서는 테스트 상점과 Kiln을 모두 설정하지 않습니다. `FAILED`는 의도된 fail-closed 결과이며 `paymentStatus=NOT_AVAILABLE`을 검사합니다.

스크립트는 임의 암호와 개발 토큰을 권한 0600의 임시 파일로 전달하고 출력하지 않습니다. 매 실행마다 고유한 `floww-f016-*` 전용 브리지 네트워크·컨테이너·이미지를 만들고 `com.floww.task=F016` 및 실행별 `com.floww.run` 레이블을 지정합니다. PostgreSQL 데이터 경로는 128 MiB tmpfs로 덮어 익명 Docker 볼륨을 생성하지 않습니다. 앱 포트는 호스트 `127.0.0.1`의 동적 포트에만 연결합니다. 생성 명령 전 리소스를 등록하므로 일부만 생성된 실패도 검사합니다. 정리 시 리소스 부재와 Docker 검사 실패를 구분하고, 두 소유 레이블을 확인한 경우에만 자신이 만든 리소스를 제거합니다. 정리 실패는 오류로 보고되므로 출력된 실행 접미사를 확인하여 해당 리소스만 수동으로 점검합니다. 스크립트는 기존 `floww_server-db-1`, 기존 볼륨 또는 `datawoods-control-plane`에 접근하지 않습니다.

명령은 0이면 모든 주장 통과, 0이 아니면 실패입니다. 출력은 검사 결과, JAR 해시, 임의 실행 ID만 포함하며 비밀값이나 원시 Docker 환경 정보는 포함하지 않습니다. 런타임 실패 시 원인을 확인하려면 해당 단계의 명령을 독립적으로 재현하고 민감 정보를 제거한 로그만 공유하십시오. 실제 컨테이너 검사 및 독립 승인은 F016 컨트롤러가 수행합니다.

2026-09-29 컨트롤러가 실제 실행하여 종료 코드 0을 확인했습니다. 검증 대상 서버 소스는 `e6eb3abb46852a2e3bbdc7723c266550fc4c0492`이며 [검증 기록](evidence/f016/runtime.json)과 [실행 로그](evidence/f016/runtime.log)에 JAR·스크립트 해시를 기록했습니다. 초기 점검에서 발견한 전용 네트워크의 포트 게시, 시작 중 연결 종료, 재시작 시 동적 포트 변경을 스크립트에 반영했습니다. 재시작 직후에는 현재 포트를 다시 조회합니다. 기존 데이터가 있는 DB의 Flyway 전환이나 전체 구매 연결은 이 검사의 범위에 포함하지 않습니다.

## English

Run `python3 scripts/container_smoke.py` with Python 3, Docker, Java 21 in `JAVA_HOME`, and locally cached `postgres:16.4-alpine` and `eclipse-temurin:21-jre` images. The script uses the pinned Maven wrapper to package the JAR, builds the repository Dockerfile with pulls disabled, and starts a fresh PostgreSQL 16.4 container plus the actual application image. Maven may fetch missing project dependencies. No host-specific JDK path is embedded.

It asserts successful Flyway V1 on a fresh database, health, authenticated create/read/evidence, cross-owner and anonymous denial, accurate missing-provider readiness and terminal failure, and persistence after app restart without a second V1 migration. Provider settings are deliberately absent: the expected execution result is `FAILED` with `MERCHANT_NOT_CONFIGURED`; no merchant, Kiln, payment, or fulfillment is exercised. A passing run is local container evidence only.

The run creates uniquely named resources on a dedicated bridge network with both `com.floww.task=F016` and a unique `com.floww.run` label. PostgreSQL is capped at 256 MiB and one CPU, and its data path uses a 128 MiB tmpfs instead of an anonymous Docker volume; the app is capped at 640 MiB and one CPU. Only the app gets a dynamic host-loopback port. Generated credentials live in temporary files with mode 0600, are not printed, and are removed. Each resource name is registered before its create command, so partial Docker failures enter cleanup. The `finally` block distinguishes confirmed absence from an inspect failure and removes an object only after both ownership labels match this run. Cleanup errors yield a failing result and identify the exact run suffix. Existing Docker containers, volumes, and images are left in place. Exit code zero means every assertion passed; any nonzero exit means runtime acceptance is still open.

The controller executed this harness successfully on 2026-09-29 against server source `e6eb3abb46852a2e3bbdc7723c266550fc4c0492`; the [record](evidence/f016/runtime.json) and [log](evidence/f016/runtime.log) identify the JAR and harness hashes. Initial attempts exposed bridge port publication, transient startup disconnects, and changing ephemeral ports after restart. The final harness includes those corrections and reads the current published port after restart. This verifies fresh-database startup and app-restart persistence, not migration of an existing populated database or an end-to-end purchase.
