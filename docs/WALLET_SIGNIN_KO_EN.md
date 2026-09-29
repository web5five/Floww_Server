# F018 wallet sign-in / 지갑 로그인

## Scope and authority / 범위와 권한

**KO** 이 구현은 지갑 소유 확인을 위한 로그인이다. 지갑 연결 또는 로그인 서명은 위임 확정, 지출 승인, 트랜잭션 서명 권한을 만들지 않는다. EOA `personal_sign`만 검증한다. 스마트 계정 EIP-1271 검증은 체인 RPC와 최태헌 담당 검토가 필요하다. Magic DID 로그인, 이메일 계정 연결(AUTH-10), 다른 로그인 방식, 결제 API는 범위 밖이다.

**EN** This implementation signs in an externally owned account (EOA) with a one-time SIWE message and an EIP-191 `personal_sign` signature. Login and spending authority are separate. Smart contract signers (EIP-1271), Magic DID, account linking (AUTH-10), other login providers and payment are separate work.

Source snapshot checked on 2026-09-29 KST: [architecture 11927569 v11](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/11927569), [AUTH-00 13729845 v2](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/13729845), [AUTH-03 14188566 v1](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/14188566), [AUTH-04 13434947 v1](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/13434947). These auth pages describe proposals; F018 implements the bounded routes below against the merged `SigninResponse` without refresh tokens. The signature format follows [ERC-4361](https://eips.ethereum.org/EIPS/eip-4361) and [ERC-191](https://eips.ethereum.org/EIPS/eip-191); recovery uses pinned web3j crypto 4.12.3.

## Configuration / 설정

Wallet sign-in is disabled by default. Set these server-only environment variables before starting the application:

| Variable | Local example | Meaning |
| --- | --- | --- |
| `FLOWW_WALLET_SIGNIN_ENABLED` | `true` | Enables only the two public wallet POST routes. |
| `FLOWW_WALLET_EXAMPLE_ENABLED` | `true` | Serves the example at `/wallet-signin-example/` when sign-in is enabled. |
| `FLOWW_WALLET_ORIGIN` | `http://127.0.0.1:8080` | Exact trusted relying-party origin; domain and URI are derived from this server setting, never from `Host` or a request body. HTTPS is required except loopback. No path/query/fragment. |
| `FLOWW_WALLET_CHAIN_IDS` | `11155111` | Comma-separated positive allowed EVM chain IDs. Sepolia is a local example, not a claim about the deployed chain. |
| `JWT_SIGNING_KEY` | `<server-only-key-at-least-32-UTF8-bytes>` | Required for JWT issuance. Do not put this in browser/public environment variables. |
| `FLOWW_DB_URL`, `FLOWW_DB_USER`, `FLOWW_DB_PASSWORD` | `<local-PostgreSQL-values>` | Existing database settings. |
| `FLOWW_DEV_TOKEN_ALICE`, `FLOWW_DEV_TOKEN_BOB` | `<distinct-local-dev-tokens>` | Existing development filter startup settings; F018 does not replace that filter. |

**KO** 실제 배포 origin·체인 ID는 담당자가 확인한 뒤 설정한다. 브라우저 예시와 API는 같은 origin에서 접근한다. CORS 허용 범위를 추가하지 않았다. 환경값이 없거나 잘못되면 opt-in 기능은 실행되지 않거나 기동에 실패한다.

**EN** Configure the deployed origin and chain only after owner verification. The browser example calls the API on the same origin. No cross-origin policy was added. Invalid opt-in configuration fails startup.

## HTTP contract / 요청과 응답

`POST /api/v1/auth/wallet/nonce` with `Content-Type: application/json`:

```json
{"address":"0x<40-hex-character-wallet-address>","chainId":11155111}
```

HTTP 200 response (illustrative values):

```json
{
  "nonce":"<48-lowercase-hex-characters>",
  "message":"http://127.0.0.1:8080 wants you to sign in with your Ethereum account:\n0x<checksummed-address>\n\nSign in to Floww\n\nURI: http://127.0.0.1:8080\nVersion: 1\nChain ID: 11155111\nNonce: <nonce>\nIssued At: <ISO-8601-instant>\nExpiration Time: <ISO-8601-instant>",
  "expiresAt":"<ISO-8601-instant>"
}
```

Sign the returned `message` **exactly** as UTF-8 via EIP-1193 `personal_sign`, with parameters `[utf8Hex(message), address]` for MetaMask-compatible providers. Hex encoding changes only the RPC transport; it does not change the signed UTF-8 bytes. The SIWE first line includes the explicit scheme for loopback HTTP; the signed URI is the same configured origin. The server accepts a checksum address or a plain lower/upper hex input and puts the checksum form into the SIWE message. A mixed-case input with a bad EIP-55 checksum is rejected.

`POST /api/v1/auth/wallet/verify`:

```json
{"message":"<exact-message-returned-by-nonce>","signature":"0x<65-byte-personal-sign-signature>"}
```

HTTP 200 response shape (all values illustrative):

```json
{
  "accessToken":"<server-issued-JWT>",
  "tokenType":"Bearer",
  "expiresIn":1800,
  "isNewUser":true,
  "user":{
    "userId":"<UUID>",
    "email":null,
    "displayName":null,
    "role":"USER",
    "providers":["WALLET"],
    "wallets":[{"walletId":"<UUID>","address":"0x<checksummed-address>","walletType":"EXTERNAL","primary":true}],
    "createdAt":"<ISO-8601-instant>"
  }
}
```

There is no refresh token in the merged `SigninResponse`. The signed JWT has `sub=<userId>`, `role=USER`, `aud=client`, `iat` and `exp` through the common `JwtProvider`. Wallet identities use a canonical lowercase unique DB key; response addresses use EIP-55 checksum casing. A wallet signup creates an active USER with null email/password. It does not merge with an email account.

Errors use the common `ErrorResponse` with `reasonCode`, `code`, bilingual `message`, `taskId`, `attemptId`, and `retryable`. Input shape or malformed signature: 400 `INVALID_INPUT`; unsupported chain: 400 `CHAIN_NOT_SUPPORTED`; absent or used challenge: 401 `NONCE_INVALID`; expired challenge: 401 `NONCE_EXPIRED`; changed message: 401 `MESSAGE_MISMATCH`; wrong signer or invalid EIP-191 signature: 401 `SIGNATURE_INVALID`; suspended user: 403 `USER_SUSPENDED`; issue cap: 429 `TOO_MANY_REQUESTS`. Responses on these routes use `Cache-Control: no-store`.

The two routes require exact `application/json` with UTF-8 if a charset is provided. Raw bodies are capped at 8 KiB before JSON parsing, including chunked transfer; invalid UTF-8 and duplicate JSON keys are rejected. Unexpected fields are rejected. The cap applies to bytes, not Java characters.

**KO** nonce는 보안 난수 24바이트를 48자리 hex로 인코딩한다. DB에 원문 메시지·주소·체인·만료·사용 상태를 저장한다. 만료는 5분이고 성공 소비는 PostgreSQL 조건부 UPDATE에서 실제 시각을 다시 검사한다. 주소당 미사용 도전 5개, 전체 미만료 도전 행 10,000개(사용 완료 포함)까지만 발급하며 만료 행은 발급 시 제거한다. 여러 서버 인스턴스도 DB 잠금과 고유 제약을 공유한다. 실패한 서명은 사용자나 토큰을 만들지 않는다.

**EN** Each nonce contains 24 secure random bytes encoded as 48 hex characters. The database stores the exact challenge and a five-minute expiry. A conditional PostgreSQL update checks live database time at consumption, after identity lock waits. Admission is serialized across instances and capped at five pending challenges per address and 10,000 unexpired challenge rows globally, including consumed rows; issuance deletes expired rows. Invalid signatures create no user or token.

## Frontend handoff / 프론트 연동

The plain [HTML and JavaScript example](../examples/wallet-signin/) is served at `/wallet-signin-example/` only when both feature flags are enabled. MetaMask is the first connector. The page also discovers injected EIP-1193 wallets and optional EIP-6963 announcements, so a later reviewed Magic `rpcProvider` can enter the same `loginWithProvider` path without changing server signature verification. It requests an account, checks Sepolia in this local example, asks the wallet to sign the exact message bytes, and submits verification. It handles missing providers, user rejection, chain mismatch, account/provider/chain changes during pending requests, disconnect and retry. It keeps the access token in module memory only. It does not request a private key, auto-sign, persist a token, perform a transaction or present login as spending approval. No Magic SDK, key, DID claim or `MAGIC_EMBEDDED` identity is added here.

**F021 MetaMask acceptance UI / 메타마스크 수용 화면:** With no injected wallet the selector shows a disabled placeholder and the page tells the user to open it in a supported wallet browser. A late EIP-6963 announcement enables it; an announcement for the same provider object replaces the injected fallback while distinct provider objects remain selectable. The initial `accountsChanged` event during permission is accepted only when it agrees with the returned account. Actual account, chain, or provider changes during nonce, signing, switching, or verification invalidate the in-memory session and restore usable controls. Wallet pending and cancellation errors have concise Korean guidance without raw RPC text. On the wrong chain, the user can explicitly request `wallet_switchEthereumChain` to **Sepolia (11155111)**; successful switching rechecks the selected provider and account before a new login. The example never adds a chain or requests a transaction.

**KO:** 지갑이 없는 브라우저에는 비활성 선택 항목과 설치된 MetaMask 브라우저/지갑 앱 브라우저 안내가 보입니다. 늦게 발견된 지갑은 선택할 수 있고, 같은 공급자 객체의 중복 표시는 제거합니다. 최초 연결 이벤트와 이후 실제 계정 변경을 구분합니다. 메인넷 등 다른 체인에서는 사용자가 `Sepolia로 전환`을 눌러야 하며, 전환 후 선택 지갑·계정을 재확인하고 로그인합니다. 지갑 전환을 거절하거나 요청이 이미 진행 중일 때에는 화면에서 다시 시도할 수 있습니다. 이 동작은 서명 기반 로그인만 다룹니다.

For a Next.js integration, use the same request sequence from a client component. Keep the token in an in-memory session state or an approved server session strategy; do not copy this example into a public env value or `localStorage`. Replace the example's Sepolia constant only when the actual deployment chain is agreed. Mobile in-app injected providers can use the same EIP-1193 path; QR transport and device-specific acceptance remain unverified.

**Integration limit / 통합 한계:** `DevAuthFilter` exempts only the exact public POST routes and the explicitly enabled example GET assets. Existing business endpoints still accept only their existing development bearer tokens. Although F018 issues and locally verifies the common client JWT, shared JWT filter adoption and owner propagation are Ria's separate integration task. The JWT is **not yet proof of access to existing business endpoints**.

## Verification / 검증

On a fresh PostgreSQL 16.4 database, run the pinned wrapper with Java 21 and scoped DB settings:

```sh
JAVA_HOME=<Java-21-home> ./mvnw -q -Dtest=WalletSigninHttpTest,WalletSigninConfigTest test
JAVA_HOME=<Java-21-home> ./mvnw -q test
```

The HTTP test signs with generated ephemeral test keys and covers issuance, checksum wallet response, common JWT claims, wrong signer, modified message, malformed signature, unsupported chain, expiry, replay, concurrent replay, suspended user, no user/token side effects, live-clock deadline consumption, exact filter exemptions and example serving. A real wallet acceptance pass still needs an owner-confirmed origin and chain, a wallet app/browser, a successful `personal_sign`, a same-origin verify response and a separate business JWT filter integration check. Testnet transaction and payment evidence are outside F018.
