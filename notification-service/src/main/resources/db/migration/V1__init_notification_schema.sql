-- notification_db: notifications, processed_events
-- Flyway: notification-service/src/main/resources/db/migration/

CREATE TABLE notifications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL,                 -- без FK: users в другой БД
    type            VARCHAR(50)  NOT NULL,
    message         TEXT         NOT NULL,
    source_event_id UUID         NOT NULL,                 -- id события из Kafka
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT chk_notifications_type    CHECK (type IN ('TRANSFER_COMPLETED', 'TRANSFER_FAILED')),
    CONSTRAINT chk_notifications_status  CHECK (status IN ('NEW', 'SENT', 'FAILED')),
    CONSTRAINT chk_notifications_message CHECK (length(btrim(message)) > 0),
    CONSTRAINT chk_notifications_sent_at CHECK (sent_at IS NULL OR status = 'SENT'),
    -- Одно событие = одно уведомление, даже если Kafka доставит его повторно.
    CONSTRAINT uq_notifications_source_event UNIQUE (source_event_id)
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_new          ON notifications (created_at) WHERE status = 'NEW';

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
