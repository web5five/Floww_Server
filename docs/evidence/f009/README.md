# F009 component evidence / 컴포넌트 검증

An Orca GPT-6 Sol worker implemented the candidate review-confirmation helper and seven binding tests. The controller separately inspected the change and added six counterexample tests. The final Java 21/PostgreSQL `./mvnw -B -o verify` run passed **55 tests**, including the 13 new binding tests, with no failures, errors or skips, and packaged the application.

Orca의 GPT-6 Sol이 모듈과 검사 7개를 구현하고 컨트롤러가 별도 검토와 반례 검사 6개를 추가했습니다. 최종 Java 21/PostgreSQL 검사 55개가 모두 통과하고 패키징까지 완료됐습니다. 팀원의 수락이나 실제 사용자 승인 검증을 뜻하지 않습니다.

See [machine-readable evidence](verification.json) for suite counts, source file hashes and the locally built JAR hash. The hash describes the packaged artifact; no live purchase was executed with it. The first controller run had one test-data error (a standalone question mark is intentionally rejected as ambiguous by F008); the corrected comparison uses concrete text, with no production source workaround.

Checks cover exact term changes, missing/withdrawn confirmation input, cross-owner/task/revision reuse, creation/confirmation time, deadline equality, JSON mutation and unambiguous deterministic encoding. A matching receipt is a comparison against a **trusted-adapter input**: this component does not authenticate receipts or atomically enforce live revocation, budget reservation, signing or settlement.

다른 사용자·작업·버전의 확인 재사용, 조건 변경, 만료, JSON 변조 등을 검사했습니다. 확인 기록의 실제 인증과 최신 취소 상태 조회는 담당 어댑터의 책임이며 이 모듈은 자금 예약·서명·지급을 실행하지 않습니다.

No live Kiln call, Magic login, real-user approval, wallet transaction, testnet payment or fulfillment was performed in F009. Historical [F008 live proposal evidence](../f008/README.md) remains evidence for its own recorded source and scope.
