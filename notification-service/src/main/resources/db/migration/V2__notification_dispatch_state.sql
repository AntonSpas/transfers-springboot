-- Состояние отправки уведомлений: захват с арендой (SENDING + sending_until) и поля для повторов.

ALTER TABLE notifications
    ADD COLUMN attempts        INT          NOT NULL DEFAULT 0,  -- число неудачных отправок
    ADD COLUMN next_attempt_at TIMESTAMPTZ,                      -- раньше этого момента строку NEW не берём
    ADD COLUMN last_error      VARCHAR(500),                     -- причина последней неудачи
    ADD COLUMN sending_until   TIMESTAMPTZ;                      -- конец аренды строки в статусе SENDING

ALTER TABLE notifications
    DROP CONSTRAINT chk_notifications_status,
    ADD CONSTRAINT chk_notifications_status CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED')),
    ADD CONSTRAINT chk_notifications_attempts CHECK (attempts >= 0),
    -- Аренда есть ровно у строк в статусе SENDING: без неё такую строку никто не смог бы вернуть в работу.
    ADD CONSTRAINT chk_notifications_sending_until CHECK ((status = 'SENDING') = (sending_until IS NOT NULL));

-- Выборка диспетчера берёт и NEW, и SENDING с истёкшей арендой в порядке (created_at, id). Индекс содержит только
-- незавершённые строки; условия по времени проверяются уже по строкам таблицы.
DROP INDEX idx_notifications_new;
CREATE INDEX idx_notifications_dispatch ON notifications (created_at, id) WHERE status IN ('NEW', 'SENDING');
