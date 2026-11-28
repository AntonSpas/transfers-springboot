-- Состояние фонового добивания PENDING: сколько неудачных попыток было и когда последняя.

ALTER TABLE transfers
    ADD COLUMN retry_attempts     INT NOT NULL DEFAULT 0,  -- неудачные фоновые попытки (запросы клиента не считаются)
    ADD COLUMN last_attempt_at    TIMESTAMPTZ,             -- конец последней такой попытки
    ADD COLUMN retry_exhausted_at TIMESTAMPTZ,             -- когда о достижении предела сообщено в лог (один раз)
    ADD CONSTRAINT chk_transfers_retry_attempts CHECK (retry_attempts >= 0);

-- Добивание берёт PENDING по давности последней попытки (а если их не было, по времени создания). Индекс содержит
-- только PENDING; пределы по числу попыток и возрасту проверяются по строкам таблицы.
DROP INDEX idx_transfers_pending;
CREATE INDEX idx_transfers_pending ON transfers ((COALESCE(last_attempt_at, created_at)), id) WHERE status = 'PENDING';
