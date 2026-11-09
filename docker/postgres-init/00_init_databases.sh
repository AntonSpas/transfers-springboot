#!/bin/bash
# Выполняется один раз при первом старте Postgres на пустом томе
# (docker-entrypoint-initdb.d). Пароли берутся из окружения контейнера,
# если переменная не задана, используется значение по умолчанию для локальной разработки.
set -e

ACCOUNT_PW="${ACCOUNT_DB_PASSWORD:-account_dev_password}"
TRANSFER_PW="${TRANSFER_DB_PASSWORD:-transfer_dev_password}"
NOTIFICATION_PW="${NOTIFICATION_DB_PASSWORD:-notification_dev_password}"

# :'имя' — psql сам экранирует значение, поэтому кавычки и спецсимволы в пароле безопасны
psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname postgres \
     -v account_pw="$ACCOUNT_PW" -v transfer_pw="$TRANSFER_PW" -v notification_pw="$NOTIFICATION_PW" <<'SQL'
CREATE ROLE account_svc      LOGIN PASSWORD :'account_pw';
CREATE ROLE transfer_svc     LOGIN PASSWORD :'transfer_pw';
CREATE ROLE notification_svc LOGIN PASSWORD :'notification_pw';

CREATE DATABASE account_db      OWNER account_svc      ENCODING 'UTF8';
CREATE DATABASE transfer_db     OWNER transfer_svc     ENCODING 'UTF8';
CREATE DATABASE notification_db OWNER notification_svc ENCODING 'UTF8';

-- По умолчанию любая роль может подключиться к любой базе (право CONNECT у PUBLIC).
-- Забираем его: каждый сервис видит только свою базу, владелец сохраняет доступ.
REVOKE ALL ON DATABASE account_db      FROM PUBLIC;
REVOKE ALL ON DATABASE transfer_db     FROM PUBLIC;
REVOKE ALL ON DATABASE notification_db FROM PUBLIC;
SQL
