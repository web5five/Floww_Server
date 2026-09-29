# F025 Magic wallet compatibility / Magic 지갑 호환성

## Scope / 범위

**KO:** 사용자가 Magic을 선택하면 Magic의 이메일 OTP로 임베디드 지갑을 연다. 그 지갑의 `rpcProvider`가 서버에서 받은 **그대로의** SIWE 메시지에 `personal_sign`으로 서명한다. 서버는 서명에서 복원한 지갑 주소로만 신원을 결정하고 기존 공통 JWT를 발급한다. 이메일, Magic DID, 클라이언트가 표시한 제공자 이름은 서버 신원·계정 연결·결제 권한이 아니다. MetaMask 화면과 서버 공통 인증 파일은 변경하지 않았다.

**EN:** The optional Magic choice opens an embedded wallet after Magic email OTP. Its `rpcProvider` signs the exact server SIWE message with `personal_sign`; the server identifies the recovered wallet address and issues its existing common JWT. Email, DID and client labels do not establish identity, link accounts or authorize spending. The MetaMask page and common server auth are unchanged.

The 2026-09-29 F022 Confluence read found [architecture 11927569 v11](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/11927569), [AUTH-00 13729845 v2](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/13729845), [AUTH-03 14188566 v1](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/14188566), and [AUTH-04 13434947 v1](https://w3ph4ai.atlassian.net/wiki/spaces/GH/pages/13434947). The AUTH pages are proposals; this example uses the implemented [F018 wallet contract](WALLET_SIGNIN_KO_EN.md) and current non-dev wallet JWT integration. Magic API choices follow its [JavaScript SDK reference](https://docs.magic.link/embedded-wallets/sdk/client-side/javascript) and [Ethereum JavaScript guide](https://docs.magic.link/embedded-wallets/blockchains/ethereum/javascript): `loginWithEmailOTP`, Sepolia network, EIP-1193 provider and `personal_sign`. The pinned dependency is `magic-sdk@33.13.0`.

## Run / 실행

Use Node.js 20+ and the package's pinned lockfile:

```sh
cd examples/wallet-signin/magic
npm ci
npm test
npm run build
npm run preview
```

Open `http://127.0.0.1:4173/`. Without `FLOWW_MAGIC_PUBLISHABLE_KEY`, the page visibly stops before Magic SDK construction, OTP or API calls. To attempt real OTP, the Magic app owner must configure a **publishable** `pk_…` key and allow the exact `http://127.0.0.1:4173` origin in Magic, then run `FLOWW_MAGIC_PUBLISHABLE_KEY=<publishable-key> npm run preview`. Never put a Magic secret key, server JWT key, OTP or user token in a client variable or repository. The key was unavailable for F022 and F025; no live OTP or browser wallet acceptance is claimed.

**KO:** 로컬 미리보기의 `/magic-config.mjs`는 형식이 맞는 `pk_` 공개 키만 브라우저에 전달한다. `sk_`처럼 비밀 키로 보이거나 형식이 틀리면 빈 키를 내보내고 로그인 버튼을 비활성화한다. **EN:** The local preview exposes only a valid `pk_` publishable key through `/magic-config.mjs`; secret-like or malformed values produce an empty key and keep sign-in disabled. This does not make a secret key safe to place in a public frontend build variable.

Start the Java server separately with the [F018 server settings](WALLET_SIGNIN_KO_EN.md), including `FLOWW_WALLET_SIGNIN_ENABLED=true`, `FLOWW_WALLET_ORIGIN=http://127.0.0.1:4173`, and `FLOWW_WALLET_CHAIN_IDS=11155111`. The standalone preview binds only `127.0.0.1:4173` and forwards only `POST /api/v1/auth/wallet/nonce` and `POST /api/v1/auth/wallet/verify` to the fixed `http://127.0.0.1:8080` backend. It caps request bodies at 8 KiB. It does not expose business routes or alter the Java auth filter. If the backend is absent, it returns a backend unavailable error; it cannot create a session.

**KO:** 배포 시에는 실제 프론트엔드 origin, 서버 `FLOWW_WALLET_ORIGIN`, Magic 앱의 허용 origin을 동일하게 확인한다. 네트워크는 이 예시에서 Sepolia `11155111`이며 Magic과 서버 허용 체인이 일치해야 한다. 다른 체인은 담당자가 검증한 HTTPS RPC URL, 동일한 체인 ID와 서버 설정을 함께 적용한다.

**EN:** Verify the deployed frontend origin against both server `FLOWW_WALLET_ORIGIN` and the Magic application's allowed origins. This example uses Sepolia `11155111`; a different chain needs an owner-reviewed HTTPS RPC URL, the matching Magic chain ID and matching server allowlist.

## Client and Next.js handoff / 클라이언트·Next.js 인계

The separate [browser page](../examples/wallet-signin/magic/index.html) imports the built module. The [client-only Next.js component](../examples/wallet-signin/magic/next-client/MagicSignin.jsx) imports the same source only on the Magic click path; install the pinned Magic package in that frontend build context. `NEXT_PUBLIC_MAGIC_PUBLISHABLE_KEY` is publishable, never a secret or server key. The local [Next rewrite example](../examples/wallet-signin/magic/next-client/next.config.mjs) forwards only the two exact auth routes to a fixed backend; review that destination and use an equivalent narrow same-origin route in deployment. For Next on `http://127.0.0.1:3000`, configure the server SIWE origin and Magic allowed origin to that exact value. There is no arbitrary URL parameter, catch-all proxy or automatic wallet linking.

Inputs: `connect(email)` with an email address, configured `pk_…` publishable key, expected frontend origin, chain ID and Magic network. First Magic OTP completes, then the provider supplies an address and chain. The connector sends `{address, chainId}` to `/nonce`, checks the returned SIWE origin, address, chain, nonce and expiry, signs the exact returned UTF-8 message bytes, and sends only `{message, signature}` to `/verify`. The required server response contains a Bearer `accessToken`, positive `expiresIn`, user ID and the signed wallet address. The connector returns only `{userId, address}` to the UI; `getAccessToken()` holds the JWT in memory and neither displays nor persists it. The DID result is discarded. `disconnect()` clears the token immediately and logs out the authenticated Magic instance. Account, chain and disconnect events invalidate pending work.

**KO:** 세션 재사용은 서버 `expiresIn`에 한정한다. 만료 시각은 `/verify` 요청 **시작**을 기준으로 계산하므로 응답 지연이 사용 시간을 늘리지 않는다. `getAccessToken()` 또는 다음 `connect()`에서 만료를 감지하면 토큰과 로그인 결과를 즉시 지우고 Magic 로그아웃을 시작한다. 새 로그인을 명시적으로 누르면 이전 로그아웃이 끝난 뒤 OTP를 시작한다. 만료 전 중복 클릭만 기존 결과를 재사용하며 JWT 내용을 클라이언트가 임의로 해독해 권한으로 삼지 않는다.

**EN:** Session reuse is bounded by the server's `expiresIn`. The expiry deadline starts when `/verify` is requested, so response latency reduces usable time. On `getAccessToken()` or a later `connect()`, an expired token and cached result are cleared immediately; a fresh explicit login waits for the prior Magic logout before starting OTP. A failed logout blocks fresh OTP until reload. Duplicate calls before expiry reuse the current result. The client does not decode unverified JWT claims as authority.

Magic's embedded UI needs its own frames and connections. The existing Java-served MetaMask example has a restrictive self-only CSP, so it is intentionally **not** used as the Magic host. The standalone preview does not define a production CSP. For a hosted frontend, inspect the current domains used by the selected Magic app and set narrow `frame-src`, `connect-src` and `script-src` permissions through its security review; validate actual OTP in a browser. Avoid wildcard permissions. Magic's [custom-node guidance](https://magic.link/docs/blockchains/featured-chains/ethereum/javascript) also requires its app CSP to allow an owner-selected custom RPC URL.

## Acceptance and ownership / 인수와 담당

The local package's 19 tests cover missing key, OTP cancellation, exact signature bytes, Sepolia, origin/address/chain mismatch, stale completion, account change, malformed response, bounded token reuse, request-delay expiry, delayed and failed logout/reconnect, and preview key filtering. They use a fake SDK/provider and synthetic HTTP responses. The pinned build passes. A no-key browser preview showed the login button disabled and the setup message; only local page assets were requested. Separately, controller-reported F024 packaged-backend checks established wallet-issued JWT profile/execution owner isolation using synthetic EOA signatures; this Magic connector was not part of that HTTP proof.

실제 Magic 앱 공개 키와 허용 origin이 없어 F025에서는 OTP 완료, Magic 지갑 서명, 브라우저에서의 서버 검증을 확인하지 못했다. Geondong Kim 또는 Sinwoo Park가 앱 설정과 CSP를 준비한 뒤 실제 OTP, 지갑 주소, 서버 nonce/verify, 로그아웃 및 재시도를 확인해야 한다. **EN:** A real acceptance pass still needs the app owner to supply a publishable key, configure origin/CSP, complete OTP, inspect the wallet address and server nonce/verify exchange, and verify logout plus retry. Ria Choi owns common JWT/auth integration; Taeheon Choi owns smart-account signer review; Michael and the controller own release review. This package does not evidence a live Magic OTP, transaction, payment, fulfillment, or user acceptance.
