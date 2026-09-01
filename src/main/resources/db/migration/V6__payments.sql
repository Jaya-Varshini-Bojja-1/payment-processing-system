-- M5: PaymentTransaction (gateway-backed deposit/withdrawal) + system
-- clearing account.
--
-- A deposit credits the user's account and debits the clearing account;
-- a withdrawal debits the user and credits clearing (see LedgerService
-- call sites in the payment module). Net deposits over withdrawals mean
-- the clearing account is EXPECTED to run negative — the opposite
-- invariant of every user account — so it needs its own row in `accounts`
-- (reusing the exact same table/entity, not a parallel concept) with a
-- constraint that only applies to ordinary user accounts.
ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_account_type CHECK (account_type IN ('USER', 'CLEARING'));

-- Replaces ck_accounts_balance_non_negative (M2): still non-negative for
-- every USER account, but no longer enforced for CLEARING.
ALTER TABLE accounts
    DROP CONSTRAINT ck_accounts_balance_non_negative;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_balance_non_negative
        CHECK (account_type = 'CLEARING' OR balance_paise >= 0);

-- At most one clearing account per currency (only INR exists in V1).
CREATE UNIQUE INDEX uq_accounts_one_clearing_per_currency
    ON accounts (currency)
    WHERE account_type = 'CLEARING';

-- Seed a fixed system user + its clearing account. A real `users` row is
-- used (rather than relaxing accounts.user_id to nullable, or inventing a
-- second owner concept) so `accounts` keeps exactly one shape and every
-- existing query/repository stays correct without a special case. The
-- password hash is a value BCrypt can never produce for a real password,
-- and login is additionally blocked by application code checking
-- account_type before any auth flow — this user only ever exists to be
-- referenced by accounts.user_id.
INSERT INTO users (id, email, password_hash, role, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'system-clearing@payment-processing-system.internal',
    '!disabled!',
    'ADMIN',
    'DISABLED'
);

INSERT INTO accounts (id, user_id, currency, balance_paise, account_type)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'INR',
    0,
    'CLEARING'
);

-- PaymentTransaction: gateway-backed deposit/withdrawal, distinct from
-- Transfer (see PROJECT_PLAN.md naming rule). No ledger post exists until
-- the (simulated) gateway call succeeds — see the payment module's
-- PaymentExecutionService / PaymentOutcomeService — so a row sitting in
-- PENDING/PROCESSING/RETRYING has, by construction, moved no money yet.
CREATE TABLE payment_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id),
    account_id          UUID NOT NULL REFERENCES accounts (id),
    type                VARCHAR(16) NOT NULL,
    amount_paise        BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    status              VARCHAR(24) NOT NULL,
    gateway_reference   VARCHAR(64),
    attempt_count       INT NOT NULL DEFAULT 0,
    failure_reason      VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_payment_transactions_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_payment_transactions_currency_inr_only CHECK (currency = 'INR'),
    CONSTRAINT ck_payment_transactions_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RETRYING', 'REQUIRES_MANUAL_REVIEW')
    )
);

CREATE INDEX idx_payment_transactions_user ON payment_transactions (user_id, created_at DESC);

-- idempotency_records.resource_type is already generic (see V4); this
-- module reuses it as-is with resource_type = 'PAYMENT_TRANSACTION' —
-- no migration change needed for idempotency, outbox_events, or
-- inbox_events, all of which were already built generic in M3/M4.
