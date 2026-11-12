-- account_db: идемпотентность пополнений (заголовок Idempotency-Key у POST /accounts/{id}/deposits)
-- Flyway: account-service/src/main/resources/db/migration/

-- NULL у проводок переводов (их защищает UNIQUE (transfer_id, type)) и у пополнений, сделанных до этой миграции.
-- ADD COLUMN не затрагивает строки, поэтому триггер append-only не срабатывает.
ALTER TABLE account_transactions ADD COLUMN idempotency_key VARCHAR(100);

-- Ключ уникален в пределах счёта. Частичный индекс: проводки без ключа в нём не участвуют.
-- Сервис проверяет ключ под блокировкой счёта, а этот индекс остаётся последним рубежом.
CREATE UNIQUE INDEX uq_acc_tx_account_idem
    ON account_transactions (account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Ключ имеет смысл только у пополнений и не может быть пустым.
ALTER TABLE account_transactions
    ADD CONSTRAINT chk_acc_tx_idem_only_deposit CHECK (idempotency_key IS NULL OR type = 'DEPOSIT'),
    ADD CONSTRAINT chk_acc_tx_idem_not_blank    CHECK (idempotency_key IS NULL OR length(btrim(idempotency_key)) > 0);
