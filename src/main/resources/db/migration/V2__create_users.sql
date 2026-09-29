-- Issue #22: 이메일 로그인 + USER/ADMIN Role 구분 (AUTH-00, AUTH-05, AUTH-06, AUTH-11)
--
-- 규칙
-- * id는 앱에서 UUID.randomUUID()로 만든다 (ExecutionStore와 같은 방식).
-- * email은 앱에서 trim + 소문자로 정규화해 저장한다. 아래 CHECK는 정규화가 빠진 저장을 막는 안전장치다.
-- * role·status·provider 값은 auth 패키지의 enum 이름과 같아야 한다 (UserRole, UserStatus, AuthProvider).
--   enum에 값을 추가할 때는 새 V 파일에서 해당 CHECK 제약을 DROP 후 다시 만든다. 이 파일은 수정하지 않는다.
-- * ADMIN 계정·비밀번호·해시를 이 파일에 넣지 않는다. ADMIN은 서버 기동 시 env로만 생성한다.

CREATE TABLE users (
                       id              UUID PRIMARY KEY,
                       email           VARCHAR(254) NOT NULL,
                       password_hash   VARCHAR(100),
                       role            VARCHAR(16)  NOT NULL DEFAULT 'USER',
                       status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
                       provider        VARCHAR(32)  NOT NULL DEFAULT 'EMAIL',
                       display_name    VARCHAR(100),
                       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

                       CONSTRAINT users_email_key
                           UNIQUE (email),
                       CONSTRAINT users_email_normalized_check
                           CHECK (email = lower(btrim(email)) AND email <> ''),
                       CONSTRAINT users_role_check
                           CHECK (role IN ('USER', 'ADMIN')),
                       CONSTRAINT users_status_check
                           CHECK (status IN ('ACTIVE', 'SUSPENDED')),
                       CONSTRAINT users_provider_check
                           CHECK (provider IN ('EMAIL')),
    -- 이메일 계정은 비밀번호 해시가 반드시 있어야 한다.
    -- GOOGLE·WALLET 등 비밀번호 없는 provider를 추가할 때를 대비해 컬럼 자체는 NULL 허용으로 둔다.
                       CONSTRAINT users_email_password_check
                           CHECK (provider <> 'EMAIL' OR password_hash IS NOT NULL)
);