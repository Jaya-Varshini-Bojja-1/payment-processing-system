-- M3: P2P Transfer + client idempotency.
--
-- idempotency_records is intentionally generic (resource_type/resource_id)
-- rather than transfer-specific: M5's PaymentTransaction (deposit/withdraw)
-- will reuse this exact table rather than inventing a second idempotency
-- mechanism. PostgreSQL is the authoritative store (see IdempotencyService);
-- Redis is only ever a best-effort read-through cache in front of it.
--
-- The UNIQUE(user_id, idempotency_key) constraint is what actually prevents
-- two concurrent requests with the same key from both creating a Transfer:
-- whichever INSERT loses the race gets a constraint violation, not a
-- successful duplicate row. That guarantee holds even with Redis disabled.
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    resource_type   VARCHAR(32) NOT NULL,
    resource_id     UUID,
    status          VARCHAR(16) NOT NULL,
    http_status     INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

-- destination_user_id duplicates information already reachable via
-- destination_account_id -> accounts.user_id. Accounts never change owners
-- in V1, so this denormalization is safe and lets "transfers involving me"
-- (initiator OR recipient) be queried without a join.
CREATE TABLE transfers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    initiator_user_id       UUID NOT NULL REFERENCES users (id),
    source_account_id       UUID NOT NULL REFERENCES accounts (id),
    destination_account_id  UUID NOT NULL REFERENCES accounts (id),
    destination_user_id     UUID NOT NULL REFERENCES users (id),
    amount_paise            BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    status                  VARCHAR(16) NOT NULL,
    failure_reason          VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_currency_inr_only CHECK (currency = 'INR'),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    -- Defense-in-depth: the application also rejects self-transfers before
    -- ever reaching the database, but a DB-level guard costs nothing and
    -- protects against future code paths that might forget to check.
    CONSTRAINT ck_transfers_not_self CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_transfers_initiator ON transfers (initiator_user_id, created_at DESC);
CREATE INDEX idx_transfers_destination_user ON transfers (destination_user_id, created_at DESC);
