-- transfer_db: transfers, outbox_events
-- Flyway: transfer-service/src/main/resources/db/migration/

CREATE TABLE transfers (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),   -- он же transfer_id в account-service
    idempotency_key VARCHAR(100)   NOT NULL,
    initiator_id    UUID           NOT NULL,                -- sub из JWT (без FK: users в другой БД)
    from_account_id UUID           NOT NULL,                -- без FK: accounts в другой БД
    to_account_id   UUID           NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_transfers_amount     CHECK (amount > 0),
    CONSTRAINT chk_transfers_currency   CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_transfers_status     CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_transfers_accounts   CHECK (from_account_id <> to_account_id),
    CONSTRAINT chk_transfers_failure    CHECK (failure_reason IS NULL OR status = 'FAILED'),
    CONSTRAINT chk_transfers_key_length CHECK (length(btrim(idempotency_key)) > 0),
    -- Идемпотентность запроса клиента: тот же ключ от того же пользователя = тот же перевод.
    CONSTRAINT uq_transfers_idempotency UNIQUE (initiator_id, idempotency_key)
);
CREATE INDEX idx_transfers_from_created      ON transfers (from_account_id, created_at DESC);
CREATE INDEX idx_transfers_to_created        ON transfers (to_account_id,   created_at DESC);
CREATE INDEX idx_transfers_initiator_created ON transfers (initiator_id,    created_at DESC);
-- Поиск «зависших» переводов для повторной обработки.
CREATE INDEX idx_transfers_pending           ON transfers (created_at) WHERE status = 'PENDING';

CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),        -- он же event_id для консьюмера
    aggregate_id UUID         NOT NULL,                                     -- id перевода; ключ партиции в Kafka
    type         VARCHAR(100) NOT NULL,                                     -- TransferCompleted, TransferFailed
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
-- Publisher читает только неотправленные события, поэтому индекс частичный.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox_events (aggregate_id);
