-- M1: identity tables. Users own credentials + role; refresh tokens are
-- stored hashed (never raw) with rotation tracked via replaced_by_token_id.

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- Refresh tokens are opaque random secrets; only their SHA-256 hash is
-- persisted. token_hash is unique so a stolen hash cannot be inserted twice,
-- and rotation forms a linked chain via replaced_by_token_id so reuse of an
-- already-rotated token is detectable.
CREATE TABLE refresh_tokens (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash             VARCHAR(255) NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    replaced_by_token_id   UUID REFERENCES refresh_tokens (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
