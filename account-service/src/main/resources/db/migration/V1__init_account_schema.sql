-- account_db: users, currencies, accounts, account_transactions, audit_log
-- Flyway: account-service/src/main/resources/db/migration/

-- ---------------------------------------------------------------- currencies
CREATE TABLE currencies (
    code        CHAR(3)      PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    minor_units SMALLINT     NOT NULL DEFAULT 2,
    CONSTRAINT chk_currencies_code        CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_currencies_minor_units CHECK (minor_units BETWEEN 0 AND 4)
);

-- --------------------------------------------------------------------- users
CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    roles         TEXT[]       NOT NULL DEFAULT ARRAY['USER'],
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_email_not_blank CHECK (length(btrim(email)) > 0),
    CONSTRAINT chk_users_roles_not_empty CHECK (cardinality(roles) > 0)
);
-- Email без учёта регистра: Anton@x.com и anton@x.com — один пользователь.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- ------------------------------------------------------------------ accounts
CREATE TABLE accounts (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   UUID           NOT NULL REFERENCES users (id)      ON DELETE RESTRICT,
    currency   CHAR(3)        NOT NULL REFERENCES currencies (code),
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    version    BIGINT         NOT NULL DEFAULT 0,          -- @Version (оптимистичная блокировка)
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0)
);
CREATE INDEX idx_accounts_owner ON accounts (owner_id);

-- ------------------------------------------------------ account_transactions
CREATE TABLE account_transactions (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID           NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    type          VARCHAR(20)    NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    transfer_id   UUID,                                    -- id перевода из transfer_db (без FK: другая БД)
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_acc_tx_type          CHECK (type IN ('DEPOSIT', 'TRANSFER_DEBIT', 'TRANSFER_CREDIT')),
    CONSTRAINT chk_acc_tx_amount        CHECK (amount > 0),
    CONSTRAINT chk_acc_tx_balance_after CHECK (balance_after >= 0),
    -- Для переводов transfer_id обязателен, для пополнений его быть не должно.
    CONSTRAINT chk_acc_tx_transfer_id   CHECK ((type IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT')) = (transfer_id IS NOT NULL)),
    -- Защита от двойного списания/зачисления при повторном вызове по одному переводу.
    -- У пополнений transfer_id = NULL, а NULL в UNIQUE считаются разными, поэтому они не конфликтуют.
    CONSTRAINT uq_acc_tx_transfer_type  UNIQUE (transfer_id, type)
);
-- Выписка по счёту; id добавлен для стабильной пагинации при одинаковом created_at.
CREATE INDEX idx_acc_tx_account_created ON account_transactions (account_id, created_at DESC, id DESC);

-- ----------------------------------------------------------------- audit_log
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    UUID,                                      -- NULL = системное действие; без FK, чтобы журнал не зависел от users
    action      VARCHAR(50)  NOT NULL,                     -- CREATE_ACCOUNT, DEPOSIT, LOGIN ...
    entity_type VARCHAR(50)  NOT NULL,                     -- ACCOUNT, USER ...
    entity_id   UUID,
    details     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_actor  ON audit_log (actor_id, created_at DESC) WHERE actor_id IS NOT NULL;

-- ------------------------------------------- неизменяемость финансовых записей
-- Проводки и журнал аудита только добавляются: исправления делаются новыми записями.
CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_acc_tx_append_only
    BEFORE UPDATE OR DELETE ON account_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
