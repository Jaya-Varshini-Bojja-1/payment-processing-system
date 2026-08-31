-- M2: Account + Ledger core.
--
-- Financial model: the ledger (ledger_transactions + ledger_entries) is the
-- source of truth for money. accounts.balance_paise is a materialized cache
-- of "what the ledger says right now" for fast reads — it is always updated
-- in the SAME application-level transaction as the ledger rows that justify
-- the change (see LedgerService), never independently.

-- One wallet per user in V1 (see PROJECT_PLAN.md section on scope).
-- balance_paise >= 0 holds for M2 because only user accounts exist; a future
-- system clearing account (M5, deposits/withdrawals) will need its own
-- constraint story since it is expected to run negative.
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id),
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
    balance_paise   BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_user_id UNIQUE (user_id),
    CONSTRAINT ck_accounts_currency_inr_only CHECK (currency = 'INR'),
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance_paise >= 0)
);

-- Journal header: one row per balanced money-movement event. reference_type
-- + reference_id let a future caller (Transfer, PaymentTransaction, ...)
-- tag "which business operation produced this post" so a duplicate call
-- (bug, retry, replay) fails loudly at the DB instead of double-posting.
-- This is a defense-in-depth guard, NOT the primary client-facing
-- idempotency mechanism (that's idempotency_records, added in M3).
CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_type  VARCHAR(64),
    reference_id    UUID,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial unique index: only enforced when a reference is actually supplied,
-- so ad-hoc/system posts (e.g. this milestone's own tests) can omit it.
CREATE UNIQUE INDEX uq_ledger_transactions_reference
    ON ledger_transactions (reference_type, reference_id)
    WHERE reference_id IS NOT NULL;

-- Journal lines: immutable, insert-only. entry_type is from the account
-- HOLDER's point of view: CREDIT increases the account's balance (money in),
-- DEBIT decreases it (money out). Every ledger_transaction's entries must
-- have SUM(amount WHERE CREDIT) = SUM(amount WHERE DEBIT); LedgerService
-- enforces this before insert, not the database (Postgres CHECK constraints
-- cannot see sibling rows).
CREATE TABLE ledger_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id   UUID NOT NULL REFERENCES ledger_transactions (id),
    account_id              UUID NOT NULL REFERENCES accounts (id),
    entry_type              VARCHAR(6) NOT NULL,
    amount_paise            BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_ledger_entries_currency_inr_only CHECK (currency = 'INR')
);

CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_ledger_transaction ON ledger_entries (ledger_transaction_id);

-- Immutability, enforced at the database itself (not just "we didn't write
-- an update method"): any UPDATE or DELETE against the ledger tables is
-- rejected outright, even from a future admin tool or a buggy migration.
CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable: % on % is not permitted', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
