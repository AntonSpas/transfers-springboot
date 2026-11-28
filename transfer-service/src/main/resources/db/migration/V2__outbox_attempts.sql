-- «Парковка» событий outbox, которые нельзя отправить из-за них самих.

ALTER TABLE outbox_events
    ADD COLUMN attempts   INT          NOT NULL DEFAULT 0,  -- неудачные отправки по вине самого события
    ADD COLUMN last_error VARCHAR(500),                     -- причина последней такой неудачи
    ADD COLUMN parked_at  TIMESTAMPTZ,                      -- запаркованное событие publisher не берёт
    ADD CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0);

-- Publisher читает неотправленные и незапаркованные события в порядке (created_at, id).
DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id)
    WHERE published_at IS NULL AND parked_at IS NULL;
