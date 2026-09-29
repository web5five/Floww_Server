-- F018: additive wallet identities and single-use SIWE challenges.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users DROP CONSTRAINT users_provider_check;
ALTER TABLE users ADD CONSTRAINT users_provider_check CHECK (provider IN ('EMAIL', 'WALLET'));
ALTER TABLE users DROP CONSTRAINT users_email_password_check;
ALTER TABLE users ADD CONSTRAINT users_credentials_check CHECK (
    (provider = 'EMAIL' AND email IS NOT NULL AND password_hash IS NOT NULL)
    OR (provider = 'WALLET' AND email IS NULL AND password_hash IS NULL AND role = 'USER')
);

CREATE TABLE wallet_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    address VARCHAR(42) NOT NULL UNIQUE,
    wallet_type VARCHAR(32) NOT NULL DEFAULT 'EXTERNAL',
    is_primary BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT wallet_address_check CHECK (address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT wallet_type_check CHECK (wallet_type IN ('EXTERNAL', 'MAGIC_EMBEDDED'))
);
CREATE INDEX wallet_identities_user_id_idx ON wallet_identities(user_id);

CREATE TABLE wallet_login_challenges (
    nonce VARCHAR(64) PRIMARY KEY,
    address VARCHAR(42) NOT NULL,
    chain_id BIGINT NOT NULL CHECK (chain_id > 0),
    message VARCHAR(1024) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT challenge_address_check CHECK (address ~ '^0x[0-9a-f]{40}$')
);
CREATE INDEX wallet_login_challenges_expiry_idx ON wallet_login_challenges(expires_at);
