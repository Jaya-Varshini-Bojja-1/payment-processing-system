-- M4: Transactional outbox (dual-write fix) + inbox (consumer idempotency).
--
-- outbox_events is written in the SAME Postgres transaction as the domain
-- change it describes (see TransferEventPublisher, called from
-- TransferExecutionService right after a transfer + its ledger entries
-- commit). A separate OutboxPublisher polls PENDING rows on its own
-- schedule and publishes them to Kafka; the API layer itself never touches
-- Kafka. This is what prevents the "dual write" bug: `db.commit()`
-- succeeding while `kafka.send()` never happens (or vice versa) because the
-- process crashed between the two calls.
--
-- topic / dlq_topic are stored per-row rather than derived from event_type
-- in code, so OutboxPublisher stays a fully generic poller: it never needs
-- to know "TransferCompleted" exists, only "this PENDING row has a topic,
-- send its payload there." A future M5 event type is then just a new row
-- shape, not a publisher code change.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    dlq_topic       VARCHAR(128) NOT NULL,
    payload_json    TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    -- Defense-in-depth: the same business event (e.g. one Transfer's
    -- completion) can never be recorded twice, even if application code
    -- ever called the writer twice for the same aggregate. In the current
    -- Transfer flow this can't actually fire — each optimistic-lock retry
    -- builds a brand-new Transfer id — but the guard costs nothing.
    CONSTRAINT uq_outbox_aggregate_event UNIQUE (aggregate_type, aggregate_id, event_type)
);

-- The publisher's claim query filters on (status, next_attempt_at); this
-- index keeps "give me due rows" cheap as the table grows.
CREATE INDEX idx_outbox_status_next_attempt ON outbox_events (status, next_attempt_at);

-- inbox_events is the consumer-side mirror of the outbox. At-least-once
-- Kafka delivery means the SAME message can legitimately arrive more than
-- once (publisher retry, consumer rebalance, redelivery after a crash
-- before the offset commit). Inserting event_id here before doing any
-- domain-side work, and treating a unique-violation as "already handled,
-- skip," is what makes the consumer idempotent no matter how many times
-- Kafka redelivers the same message.
CREATE TABLE inbox_events (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    consumed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
