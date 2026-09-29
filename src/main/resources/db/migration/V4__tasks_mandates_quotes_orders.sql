-- Issue #34 (#32), #35: Task · versioned Mandate · 견적 스냅샷 · 구매 시도 · EIP-712 승인 · 주문 · 이벤트
--
-- 규칙 (데이터 명세서, 정책과 결정)
-- * 금액은 토큰 최소 단위 정수 NUMERIC(78,0). float·소수 금액을 저장하지 않는다.
-- * 상태값은 VARCHAR + CHECK. 값은 com.floww.server.task 의 enum 이름과 같아야 한다.
--   값을 추가할 때는 새 V 파일에서 CHECK 제약을 DROP 후 다시 만든다. 이 파일은 수정하지 않는다.
-- * 주소는 소문자 0x + 40 hex 로 정규화해 저장한다.
-- * Task 상태(task lifecycle)와 정책 판정(ALLOW/DENY)은 다른 컬럼·테이블에 둔다.
-- * 기존 /api/executions 테이블(executions, execution_events ...)은 건드리지 않는다.

CREATE TABLE tasks (
    id                      UUID PRIMARY KEY,
    owner_id                UUID         NOT NULL REFERENCES users(id),
    idempotency_key         VARCHAR(128) NOT NULL,
    request_hash            CHAR(64)     NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    status_reason_code      VARCHAR(64),
    goal                    TEXT         NOT NULL,
    current_mandate_version INTEGER,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT tasks_owner_idempotency_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT tasks_status_check CHECK (status IN ('DRAFT', 'AWAITING_APPROVAL', 'ACTIVE', 'EXECUTING',
        'COMPLETED', 'DECLINED', 'FAILED', 'EXPIRED', 'CANCELLED'))
);
CREATE INDEX tasks_owner_created_idx ON tasks(owner_id, created_at DESC, id DESC);

CREATE TABLE mandate_versions (
    id                      UUID PRIMARY KEY,
    task_id                 UUID         NOT NULL REFERENCES tasks(id),
    version                 INTEGER      NOT NULL CHECK (version > 0),
    status                  VARCHAR(24)  NOT NULL,
    goal                    TEXT         NOT NULL,
    item_id                 VARCHAR(128) NOT NULL,
    budget_base_units       NUMERIC(78,0) NOT NULL CHECK (budget_base_units > 0),
    budget_scope            VARCHAR(32)  NOT NULL DEFAULT 'TASK_CUMULATIVE',
    chain_id                BIGINT       NOT NULL CHECK (chain_id > 0),
    token_address           VARCHAR(42)  NOT NULL,
    token_decimals          INTEGER      NOT NULL CHECK (token_decimals BETWEEN 0 AND 36),
    allowed_recipients      JSONB        NOT NULL,
    allowed_actions         JSONB        NOT NULL,
    expires_at              TIMESTAMPTZ  NOT NULL,
    confirmed_at            TIMESTAMPTZ,
    confirmation_method     VARCHAR(32),
    authorization_reference VARCHAR(255),
    mandate_hash            CHAR(64)     NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT mandate_versions_task_version_key UNIQUE (task_id, version),
    CONSTRAINT mandate_versions_status_check CHECK (status IN ('DRAFT', 'CONFIRMED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT mandate_versions_scope_check CHECK (budget_scope IN ('TASK_CUMULATIVE')),
    CONSTRAINT mandate_versions_token_check CHECK (token_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT mandate_versions_confirmed_check CHECK (
        status <> 'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmation_method IS NOT NULL
            AND authorization_reference IS NOT NULL))
);

CREATE TABLE merchant_quotes (
    id                         UUID PRIMARY KEY,
    task_id                    UUID         NOT NULL REFERENCES tasks(id),
    merchant_id                VARCHAR(64)  NOT NULL,
    external_quote_id          VARCHAR(128) NOT NULL,
    item_id                    VARCHAR(128) NOT NULL,
    item_name                  VARCHAR(200) NOT NULL,
    quantity                   INTEGER      NOT NULL CHECK (quantity > 0),
    in_stock                   BOOLEAN      NOT NULL,
    chain_id                   BIGINT       NOT NULL,
    token_address              VARCHAR(42)  NOT NULL,
    token_decimals             INTEGER      NOT NULL,
    item_amount_base_units     NUMERIC(78,0) NOT NULL CHECK (item_amount_base_units >= 0),
    delivery_fee_base_units    NUMERIC(78,0) NOT NULL CHECK (delivery_fee_base_units >= 0),
    total_amount_base_units    NUMERIC(78,0) NOT NULL CHECK (total_amount_base_units > 0),
    -- 판매자 견적이 말한 수취 주소 (신뢰하지 않는 데이터, 증거용 스냅샷)
    quoted_pay_to_address      VARCHAR(42)  NOT NULL,
    -- 견적 수집 시점 서버 레지스트리 주소 (신뢰하는 매핑)
    registry_recipient_address VARCHAR(42)  NOT NULL,
    evidence_mode              VARCHAR(64)  NOT NULL,
    quoted_at                  TIMESTAMPTZ  NOT NULL,
    expires_at                 TIMESTAMPTZ  NOT NULL,
    promised_fulfillment_at    TIMESTAMPTZ  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT merchant_quotes_task_external_key UNIQUE (task_id, external_quote_id),
    CONSTRAINT merchant_quotes_total_check
        CHECK (total_amount_base_units = item_amount_base_units + delivery_fee_base_units),
    CONSTRAINT merchant_quotes_address_check CHECK (quoted_pay_to_address ~ '^0x[0-9a-f]{40}$'
        AND registry_recipient_address ~ '^0x[0-9a-f]{40}$' AND token_address ~ '^0x[0-9a-f]{40}$')
);
CREATE INDEX merchant_quotes_task_expiry_idx ON merchant_quotes(task_id, expires_at);

CREATE TABLE execution_attempts (
    id                  UUID PRIMARY KEY,
    task_id             UUID         NOT NULL REFERENCES tasks(id),
    mandate_id          UUID         NOT NULL REFERENCES mandate_versions(id),
    -- 제안된 quoteId가 이 Task의 견적이 아니면 NULL (UNKNOWN_QUOTE_ID로 DENY 기록)
    quote_id            UUID         REFERENCES merchant_quotes(id),
    proposed_quote_ref  VARCHAR(128) NOT NULL,
    proposed_by         VARCHAR(16)  NOT NULL,
    -- AI/클라이언트가 제안에 넣은 수취 주소 (신뢰하지 않음, 레지스트리와 비교만 한다)
    proposed_recipient  VARCHAR(128),
    status              VARCHAR(32)  NOT NULL,
    policy_decision     VARCHAR(8)   NOT NULL,
    reason_code         VARCHAR(64),
    policy_version      VARCHAR(32)  NOT NULL,
    exact_payload_hash  CHAR(64)     NOT NULL,
    amount_base_units   NUMERIC(78,0) CHECK (amount_base_units > 0),
    recipient_address   VARCHAR(42),
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT execution_attempts_proposer_check CHECK (proposed_by IN ('AI', 'USER')),
    CONSTRAINT execution_attempts_status_check CHECK (status IN ('POLICY_ALLOWED', 'BLOCKED', 'APPROVED',
        'ORDERED', 'SUPERSEDED')),
    CONSTRAINT execution_attempts_decision_check CHECK (policy_decision IN ('ALLOW', 'DENY')),
    CONSTRAINT execution_attempts_deny_reason_check CHECK (policy_decision = 'ALLOW' OR reason_code IS NOT NULL),
    CONSTRAINT execution_attempts_allow_quote_check CHECK (policy_decision = 'DENY'
        OR (quote_id IS NOT NULL AND amount_base_units IS NOT NULL AND recipient_address IS NOT NULL))
);
CREATE INDEX execution_attempts_task_idx ON execution_attempts(task_id, started_at, id);

-- EIP-712 승인 요청. 서버가 채운 typed data와 1회용 nonce. consumed_at 은 한 번만 채워진다.
CREATE TABLE approval_nonces (
    nonce        CHAR(66)     PRIMARY KEY,
    task_id      UUID         NOT NULL REFERENCES tasks(id),
    attempt_id   UUID         NOT NULL REFERENCES execution_attempts(id),
    mandate_id   UUID         NOT NULL REFERENCES mandate_versions(id),
    typed_data   JSONB        NOT NULL,
    digest       CHAR(66)     NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    issued_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT approval_nonces_format_check CHECK (nonce ~ '^0x[0-9a-f]{64}$')
);
CREATE INDEX approval_nonces_attempt_idx ON approval_nonces(attempt_id);

-- 검증을 통과한 승인 기록. 비밀키·토큰 원문은 저장하지 않는다.
CREATE TABLE mandate_approvals (
    id              UUID PRIMARY KEY,
    task_id         UUID         NOT NULL REFERENCES tasks(id),
    mandate_id      UUID         NOT NULL REFERENCES mandate_versions(id),
    attempt_id      UUID         NOT NULL UNIQUE REFERENCES execution_attempts(id),
    nonce           CHAR(66)     NOT NULL UNIQUE REFERENCES approval_nonces(nonce),
    method          VARCHAR(32)  NOT NULL DEFAULT 'EIP712',
    typed_data      JSONB        NOT NULL,
    digest          CHAR(66)     NOT NULL,
    signature       VARCHAR(132) NOT NULL,
    signer_address  VARCHAR(42)  NOT NULL,
    signed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT mandate_approvals_method_check CHECK (method IN ('EIP712')),
    CONSTRAINT mandate_approvals_signer_check CHECK (signer_address ~ '^0x[0-9a-f]{40}$')
);

-- 판매자 주문. 주문 생성은 결제·서명을 실행하지 않는다 (payment_status 는 지급 구현 전까지 NOT_ATTEMPTED).
CREATE TABLE merchant_orders (
    id                  UUID PRIMARY KEY,
    task_id             UUID         NOT NULL REFERENCES tasks(id),
    attempt_id          UUID         NOT NULL UNIQUE REFERENCES execution_attempts(id),
    quote_id            UUID         NOT NULL REFERENCES merchant_quotes(id),
    approval_id         UUID         NOT NULL REFERENCES mandate_approvals(id),
    merchant_id         VARCHAR(64)  NOT NULL,
    external_order_id   VARCHAR(128) NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    request_hash        CHAR(64)     NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    payment_status      VARCHAR(24)  NOT NULL DEFAULT 'NOT_ATTEMPTED',
    amount_base_units   NUMERIC(78,0) NOT NULL CHECK (amount_base_units > 0),
    recipient_address   VARCHAR(42)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT merchant_orders_task_idempotency_key UNIQUE (task_id, idempotency_key),
    CONSTRAINT merchant_orders_status_check CHECK (status IN ('ACCEPTED', 'CANCELLED')),
    CONSTRAINT merchant_orders_payment_status_check CHECK (payment_status IN ('NOT_ATTEMPTED'))
);

CREATE TABLE task_events (
    seq          BIGSERIAL PRIMARY KEY,
    task_id      UUID         NOT NULL REFERENCES tasks(id),
    attempt_id   UUID         REFERENCES execution_attempts(id),
    kind         VARCHAR(48)  NOT NULL,
    state        VARCHAR(32),
    reason_code  VARCHAR(64),
    actor        VARCHAR(32)  NOT NULL,
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX task_events_task_seq_idx ON task_events(task_id, seq);
