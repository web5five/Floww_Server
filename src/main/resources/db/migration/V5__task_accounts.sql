-- F031: one selected purchase/account per Task. No change to V1-V4 or legacy orders.
CREATE TABLE task_accounts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE REFERENCES tasks(id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES execution_attempts(id),
    mandate_id UUID NOT NULL REFERENCES mandate_versions(id),
    mandate_version INTEGER NOT NULL,
    quote_id UUID NOT NULL REFERENCES merchant_quotes(id),
    owner_address VARCHAR(42) NOT NULL,
    account_address VARCHAR(42) UNIQUE,
    deploy_tx_hash CHAR(66) UNIQUE,
    chain_id BIGINT NOT NULL CHECK (chain_id = 11155111),
    chain_task_id CHAR(66) NOT NULL,
    review_digest CHAR(66) NOT NULL,
    review_typed_data JSONB NOT NULL,
    token_address VARCHAR(42) NOT NULL,
    recipient_address VARCHAR(42) NOT NULL,
    executor_address VARCHAR(42) NOT NULL,
    reporter_address VARCHAR(42) NOT NULL,
    amount_base_units NUMERIC(78,0) NOT NULL CHECK (amount_base_units > 0),
    quote_expires_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PREPARED','BOUND','SIGNED','APPROVAL_UNKNOWN','APPROVED','PAYMENT_UNKNOWN','PAID','FULFILLMENT_UNKNOWN','COMPLETED')),
    approval_nonce NUMERIC(78,0),
    approval_digest CHAR(66),
    approval_signature VARCHAR(132),
    payment_id CHAR(66) UNIQUE,
    payment_tx_hash CHAR(66) UNIQUE,
    payment_verified_at TIMESTAMPTZ,
    fulfillment_id VARCHAR(128),
    fulfillment_evidence_hash CHAR(66),
    fulfillment_tx_hash CHAR(66) UNIQUE,
    fulfillment_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT task_accounts_bound_check CHECK (state = 'PREPARED' OR (account_address IS NOT NULL AND deploy_tx_hash IS NOT NULL)),
    CONSTRAINT task_accounts_approval_check CHECK (state IN ('PREPARED','BOUND') OR (approval_digest IS NOT NULL AND approval_signature IS NOT NULL))
);
CREATE TABLE task_account_operations (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES task_accounts(id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('APPROVAL','PAYMENT','FULFILLMENT')),
    signer_address VARCHAR(42) NOT NULL,
    signer_nonce NUMERIC(78,0) NOT NULL,
    raw_transaction TEXT NOT NULL,
    tx_hash CHAR(66) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('UNKNOWN','VERIFIED','REVERTED','MISMATCH')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, kind),
    UNIQUE (signer_address, signer_nonce)
);
CREATE TABLE task_account_signer_nonces (
    signer_address VARCHAR(42) PRIMARY KEY,
    next_nonce NUMERIC(78,0) NOT NULL
);
ALTER TABLE merchant_orders DROP CONSTRAINT merchant_orders_payment_status_check;
ALTER TABLE merchant_orders ADD CONSTRAINT merchant_orders_payment_status_check
    CHECK (payment_status IN ('NOT_ATTEMPTED','UNKNOWN','PAID'));
