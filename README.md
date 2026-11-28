# transfers-springboot

Мини-платёжное ядро на Spring Boot: счета, идемпотентные переводы между ними и уведомления о результате.
Учебный проект для отработки микросервисной архитектуры, согласованности данных и тестирования на реальных зависимостях.

Репозиторий: https://github.com/AntonSpas/transfers-springboot

## Архитектура

```
Клиент ──JWT──▶ transfer-service ──REST──▶ account-service
                     │  (transfers, outbox)      (users, accounts, проводки, выдача JWT)
                     └──Kafka: transfers.events.v1──▶ notification-service
```

| Модуль | Назначение | БД | Порт (хост) |
|---|---|---|---|
| `account-service` | счета, проводки, пользователи, выдача JWT | `account_db` | 8081 |
| `transfer-service` | приём переводов, идемпотентность, outbox, публикация событий | `transfer_db` | 8082 |
| `notification-service` | потребление событий, создание уведомлений | `notification_db` | 8083 |
| `events-contract` | общий контракт событий Kafka (только records и константы) | — | — |

Инфраструктура: PostgreSQL 17 (один контейнер, три базы, у каждого сервиса своя роль и доступ только к своей базе), Apache Kafka 4.1 (KRaft), Redis 8. transfer-service Redis не использует; он понадобится позже для лимитов переводов на пользователя.

## Требования

Java 21, Maven 3.9+, Docker (с Compose v2).

## Быстрый старт

```bash
# 0. Ключи JWT (один раз): закрытый нужен account-service, открытый и ему, и transfer-service. keys/ в .gitignore
./scripts/gen-keys.sh

# 1. Инфраструктура (Postgres, Kafka, Redis). Значения по умолчанию подходят для локальной разработки;
#    чтобы переопределить пароли: cp .env.example .env
docker compose up -d

# 2. Сборка и тесты (Testcontainers поднимает свой PostgreSQL и Kafka, нужен запущенный Docker;
#    образ apache/kafka:4.1.1 скачивается при первом запуске тестов transfer-service)
mvn verify

# 3a. Сервисы из IDE: запустите *Application классы (порты 8081/8082/8083 заданы по умолчанию).
#     account-service нужны пути JWT_PRIVATE_KEY_PATH и JWT_PUBLIC_KEY_PATH, transfer-service только JWT_PUBLIC_KEY_PATH (см. .env.example)
# 3b. ...или всё в контейнерах:
docker compose --profile apps up -d --build

# Проверка
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

Полный сброс (в том числе данных БД): `docker compose --profile apps down -v`.
Пароли БД применяются только при первом создании тома, после их смены нужен `down -v`.

Остановить только сервисы приложения, не трогая Postgres, Kafka и Redis:
`docker compose stop account-service transfer-service notification-service`
(`down` с `--profile apps` удаляет и инфраструктуру с общей сетью, а `restart: unless-stopped` поднимет контейнеры снова после перезапуска Docker).

Порт хоста 8081 один: если контейнер account-service запущен, то `mvn spring-boot:run` на этом же порту не стартует. Остановите контейнер или задайте другой порт: `SERVER_PORT=8091`.

## account-service

Владелец балансов и проводок, выдаёт JWT. Все эндпоинты, кроме `/auth/*` и health, требуют `Authorization: Bearer <token>`.

| Метод и путь | Назначение |
|---|---|
| `POST /auth/register` `{email, password}` | регистрация, сразу возвращает токен; 201 |
| `POST /auth/login` `{email, password}` | вход; 200 |
| `POST /accounts` `{currency}` | создать счёт текущего пользователя |
| `GET /accounts` | свои счета: по валюте, затем по времени открытия; без пагинации |
| `GET /accounts/{id}`, `GET /accounts/{id}/balance` | счёт, баланс (только владельцу) |
| `POST /accounts/{id}/deposits` | пополнение (только владельцу); заголовок `Idempotency-Key` обязателен |
| `GET /accounts/{accountId}/transactions` | история операций (только владельцу), новые сверху, пагинация |

Ответ `/auth/*`: `{ userId, accessToken, tokenType, expiresIn }`. Токен — RS256, живёт 15 минут, refresh-токенов нет.
Пароль 8–72 символа. Ошибки: 409 `EMAIL_ALREADY_REGISTERED`, 401 `INVALID_CREDENTIALS`, 400 `VALIDATION_FAILED` / `INVALID_PASSWORD`;
без токена или с плохим токеном — 401 `UNAUTHORIZED`, чужой счёт — 403 `FORBIDDEN`, несуществующий — 404.

Пример:

```bash
TOKEN=$(curl -s -X POST localhost:8081/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"a@example.com","password":"password123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s -X POST localhost:8081/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"currency":"RUB"}'
```

Внутреннее API для transfer-service: `POST /internal/transfers` — атомарный перевод между счетами в одной транзакции;
в запросе есть `initiatorId`, и счёт списания должен принадлежать ему (иначе 403).
Доступ только с заголовком `X-Internal-Token`, значение берётся из переменной `INTERNAL_API_TOKEN`
(по умолчанию для разработки `internal_dev_token`; в любом общем окружении задайте своё, при пустом значении приложение не стартует).

Поведение, на которое можно опираться клиенту:
- ошибки приходят в едином формате `ApiError { timestamp, status, code, message, violations[] }`;
- 201 — операция выполнена, 200 — повтор с тем же ключом и теми же параметрами (деньги не двигаются), 409 — тот же ключ с другими параметрами;
- 503 + `Retry-After: 1` — временный сбой блокировки, операция ничего не изменила, повтор с тем же ключом безопасен;
- проводки и аудит неизменяемы (триггеры БД);
- сумма пополнения и перевода не может быть точнее валюты (у RUB, USD и EUR два знака после запятой, нули в конце не считаются): 422 `INVALID_AMOUNT_PRECISION`, без округления. Повтор уже выполненной операции с тем же ключом по-прежнему возвращает сохранённый результат.

Переменные окружения: `SERVER_PORT` (по умолчанию 8081), `INTERNAL_API_TOKEN`, `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` (без значений по умолчанию: без ключей сервис не стартует); параметры подключения к БД — в `application.yml`.

Ключи JWT: `scripts/gen-keys.sh` создаёт `keys/private.pem` и `keys/public.pem`. Для запуска из IDE или `mvn spring-boot:run` укажите абсолютные пути в `.env` (Windows: прямые слэши, `C:/Projects/...`) — `.env` подхватывается через `spring.config.import`.
В Docker Compose ключи монтируются в `/keys` только для чтения, пути в контейнере заданы в `docker-compose.yml`. На Linux файл `private.pem` с правами 600 может быть недоступен пользователю внутри контейнера: тогда дайте ему чтение (`chmod 644`, только для локальной разработки).
Закрытый ключ нужен только account-service; transfer-service получает лишь `public.pem` (в Compose монтируется один этот файл, а не вся папка `keys`). Если `keys/public.pem` не создан до `docker compose up`, Docker создаст вместо файла пустую папку: сначала выполните `./scripts/gen-keys.sh`.

Интеграционные тесты идут на реальном Postgres через Testcontainers (нужен запущенный Docker). Классы называются `*Test`: в CI работает только Surefire.
Проверка одного модуля: `mvn -pl account-service -am clean verify`.

## transfer-service

Публичное API переводов. Проверяет JWT только по **публичному** ключу, хранит переводы и статусы, вызывает account-service (`POST /internal/transfers`), добивает зависшие переводы, пишет события в outbox и публикует их в Kafka. Все эндпоинты, кроме health и prometheus, требуют `Authorization: Bearer <token>` (токен выдаёт account-service). Инициатор перевода это `sub` токена, в теле запроса его нет.

| Метод и путь | Назначение |
|---|---|
| `POST /transfers` `{fromAccountId, toAccountId, amount, currency}` + заголовок `Idempotency-Key` | создать перевод; ключ обязателен, до 100 символов |
| `GET /transfers/{id}` | перевод (только инициатору) |

Ответ: `{ id, fromAccountId, toAccountId, amount, currency, status, failureReason, createdAt, updatedAt }`, `status` это `PENDING`, `COMPLETED` или `FAILED`.

Ответы `POST /transfers`:

| Код | Когда |
|---|---|
| 201 | перевод создан и обработан сейчас: `COMPLETED`, либо `FAILED` с `failureReason` (`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND`, `FORBIDDEN`, `INVALID_AMOUNT_PRECISION`) |
| 200 | повтор с тем же ключом и теми же параметрами: возвращается сохранённое состояние; если перевод был `PENDING`, он сначала дообрабатывается |
| 202 | перевод принят, но account-service недоступен: статус `PENDING`, его добьёт фоновая задача; повторите запрос с тем же ключом |
| 409 `IDEMPOTENCY_KEY_REUSED` | тот же ключ с другими параметрами; лечится только новым ключом |
| 422 `SAME_ACCOUNT` | счета совпадают |
| 400 `INVALID_IDEMPOTENCY_KEY`, `VALIDATION_FAILED` | нет или неверный ключ, неверное тело (сумма больше 0, до 15 и 4 знаков, валюта из 3 заглавных букв) |
| 502 `UPSTREAM_ERROR` | account-service ответил так, что это ошибка конфигурации или баг (например, неверный секрет); перевод остаётся `PENDING`, подробности только в логах |
| 401 `UNAUTHORIZED` | нет, просрочен или неверен токен |

`GET /transfers/{id}`: 404 `TRANSFER_NOT_FOUND`, если перевода нет; 403 `FORBIDDEN`, если он чужой (роль ADMIN обхода не даёт). Проверка существования идёт первой.

Правило для клиента то же, что в account-service: 202 и 503 значат «повторите с тем же `Idempotency-Key`».

Как устроена обработка: перевод вставляется в `PENDING` короткой транзакцией, вызов account-service идёт **вне транзакции** (таймаут соединения 2 с, чтения 8 с, до трёх попыток с тем же `transferId`), затем короткая транзакция ставит `COMPLETED` или `FAILED`, и только из `PENDING`. Если account-service недоступен, перевод остаётся `PENDING`, и раз в `interval` фоновая задача (`@Scheduled`, `FOR UPDATE SKIP LOCKED`) дообрабатывает переводы, у которых с последней попытки (или с создания) прошло `min-age`; первыми те, чья попытка давнее. account-service идемпотентен по `transferId`, поэтому повторы не двигают деньги дважды. Таймаут чтения 8 с больше `lock_timeout` account-service (5 с); при зависшем account-service `POST` может отвечать дольше 24 с.

### Предел добивания `PENDING`

После `max-attempts` (20) неудачных фоновых попыток или через `max-age` (24 ч) после создания перевод больше не берётся фоновой задачей. Он остаётся `PENDING`: в `FAILED` автоматически не переводится, потому что деньги могли уйти. В лог один раз пишется ERROR (`... reached the automatic retry limit ...`), метрика `transfer.pending.stuck` показывает число таких переводов. Повторный `POST` клиента с тем же ключом обрабатывает перевод как обычно.

С значениями по умолчанию одиночный перевод исчерпывает 20 попыток примерно за 20–30 минут недоступности account-service. Чтобы переживать более долгие сбои, увеличьте `app.pending-retry.max-attempts` (до 1000).

```bash
# какие переводы зависли
docker compose exec postgres psql -U postgres -d transfer_db \
  -c "select id, created_at, retry_attempts, last_attempt_at, retry_exhausted_at from transfers where status = 'PENDING'"
# вернуть в автоматическое добивание после устранения причины (действует, только пока перевод моложе max-age)
docker compose exec postgres psql -U postgres -d transfer_db \
  -c "update transfers set retry_attempts = 0, last_attempt_at = null, retry_exhausted_at = null where id = '<id>' and status = 'PENDING'"
```

Переменные окружения: `SERVER_PORT` (по умолчанию 8082), `JWT_PUBLIC_KEY_PATH` (без значения по умолчанию: без ключа сервис не стартует), `INTERNAL_API_TOKEN` (тот же секрет, что у account-service; по умолчанию `internal_dev_token`, пустое значение останавливает старт), `ACCOUNT_SERVICE_URL` (по умолчанию `http://localhost:8081`), `PENDING_RETRY_ENABLED` (по умолчанию `true`), `OUTBOX_ENABLED` (по умолчанию `true`), `SPRING_KAFKA_BOOTSTRAP_SERVERS` (по умолчанию `localhost:29092`, в Compose `kafka:9092`). Таймауты, число попыток и параметры добивания (`app.pending-retry.interval`, `min-age`, `batch-size`, `max-attempts`, `max-age`), параметры публикации и очистки (`app.outbox.interval`, `batch-size`, `send-timeout`, `max-attempts`, `cleanup.*`) и настройки продюсера Kafka лежат в `application.yml`.

Outbox и события: когда перевод получает финальный статус, в той же транзакции в таблицу `outbox_events` пишется событие (`TransferCompleted` или `TransferFailed`), и только если переход статуса реально произошёл (повторная обработка и гонка двух обработчиков события не дублируют). Отдельный проход раз в `app.outbox.interval` (по умолчанию 1 с) берёт неотправленные события (`FOR UPDATE SKIP LOCKED`, самые старые первыми, не больше `batch-size`), отправляет их в топик `transfers.events.v1` по порядку и ставит `published_at` после подтверждения брокера. Ключ сообщения это id перевода, заголовки: `event-type` (тип события) и `event-id` (уникальный id, по нему потребитель отбрасывает повторы). Доставка at-least-once: при сбое между отправкой и отметкой событие уйдёт повторно. Если брокер недоступен, сервис продолжает работать, события копятся в таблице и уходят после восстановления; проход обрывается на первой ошибке, чтобы не нарушать порядок (исключение: «запаркованные» события, см. ниже). `OUTBOX_ENABLED=false` отключает публикацию (и создание топика), запись в `outbox_events` при этом продолжается. Причины решений: `docs/decisions.md`, пункты 38–45 и 61–63.

Redis сервису пока не нужен: он вернётся позже для лимитов переводов на пользователя.

### Запаркованные события и очистка outbox

Если событие не отправляется из-за него самого (слишком большая запись, ошибка сериализации, недопустимая запись, метка времени или имя топика), попытки считаются в `outbox_events.attempts`. Пока их меньше `app.outbox.max-attempts` (10), проход останавливается на этом событии; затем событие «паркуется» (`parked_at`), в лог пишется ERROR, растёт `outbox.parked`, и очередь идёт дальше. Таймауты и недоступность Kafka не считаются. Ошибка топика при неверной настройке затронет все события: после исправления верните их.

```bash
# что запарковано и почему
docker compose exec postgres psql -U postgres -d transfer_db \
  -c "select id, aggregate_id, type, attempts, last_error, parked_at from outbox_events where parked_at is not null"
# вернуть в отправку после устранения причины
docker compose exec postgres psql -U postgres -d transfer_db \
  -c "update outbox_events set parked_at = null, attempts = 0 where id = '<id>'"
```

Очистка: раз в `app.outbox.cleanup.interval` (1 ч, первый проход через этот интервал после старта) удаляются события, отправленные раньше чем `retention` (7 дней) назад, партиями по `batch-size` (1000). Неотправленные и запаркованные строки не удаляются. Выключатель `app.outbox.cleanup.enabled` не зависит от `app.outbox.enabled`.

### Метрики transfer-service

`GET /actuator/prometheus`:

| Метрика (имя в Prometheus) | Что показывает |
|---|---|
| `transfer_pending_stuck` | `PENDING`, достигшие предела добивания |
| `outbox_parked_total` | запаркованные события outbox |
| `outbox_cleaned_total` | удалённые отправленные события |

### Ручная проверка transfer-service

Нужны ключи (`./scripts/gen-keys.sh`) и запущенные Postgres и account-service; transfer-service запускается из IDE (в `.env` заданы `JWT_PUBLIC_KEY_PATH`) или `docker compose --profile apps up -d --build`. Пример для bash:

```bash
reg() { curl -s -X POST localhost:8081/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"password123\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
id()  { sed -E 's/.*"id":"([^"]+)".*/\1/'; }
A=$(reg a$RANDOM@example.com); B=$(reg b$RANDOM@example.com)
acc() { curl -s -X POST localhost:8081/accounts -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d '{"currency":"RUB"}' | id; }
FROM=$(acc $A); TO=$(acc $B)
curl -s -X POST localhost:8081/accounts/$FROM/deposits -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-1' -d '{"amount":100}'

# 1) перевод: 201, COMPLETED
curl -i -X POST localhost:8082/transfers -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k-1' -d "{\"fromAccountId\":\"$FROM\",\"toAccountId\":\"$TO\",\"amount\":10,\"currency\":\"RUB\"}"
# 2) тот же ключ и тело: 200 и тот же id; другая сумма с тем же ключом: 409 IDEMPOTENCY_KEY_REUSED
# 3) GET /transfers/<id> с токеном B: 403 FORBIDDEN; с токеном A: 200; случайный UUID: 404
# 4) недостаточно средств (amount 1000000): 201 и status FAILED, failureReason INSUFFICIENT_FUNDS
# 5) остановите account-service и отправьте новый перевод: 202 и PENDING; запустите account-service снова:
#    в течение min-age + interval (около 1,5 минут) статус станет COMPLETED, это видно по GET
```

### Ручная проверка outbox и Kafka

Нужны Postgres, Kafka и account-service (см. выше) и запущенный transfer-service; сделайте перевод из предыдущего чек-листа. Команды для Compose:

```bash
# события в outbox: у свежего перевода published_at заполняется примерно через секунду
docker compose exec postgres psql -U postgres -d transfer_db \
  -c "select type, created_at, published_at from outbox_events order by created_at desc limit 5"

# что лежит в топике: ключ = id перевода, заголовки event-type и event-id, значение = JSON события
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic transfers.events.v1 --from-beginning --timeout-ms 10000 \
  --property print.key=true --property print.headers=true
```

1. Успешный перевод: в топике `TransferCompleted` с `toOwnerId` (владелец счёта получателя); перевод с `amount` больше баланса: `TransferFailed` с `reason` `INSUFFICIENT_FUNDS`.
2. Повтор запроса с тем же `Idempotency-Key` (ответ 200) и повторный `GET` новых событий не создают: событие одно на перевод.
3. Перевод в `PENDING` (account-service остановлен, ответ 202) события не создаёт; оно появится, когда фоновое добивание доведёт перевод до `COMPLETED` или `FAILED`.
4. Kafka недоступна: `docker compose stop kafka`, сделайте перевод: ответ 201, в `outbox_events` у события `published_at` пустой, в логе transfer-service примерно раз в секунду предупреждение `Outbox event ... not sent`. Затем `docker compose start kafka`: через несколько секунд `published_at` заполняется, событие появляется в топике. Если транспорт Kafka недоступен при старте transfer-service, запуск может ждать создания топика до 30 секунд, затем сервис продолжает работу (в этом случае ошибка создания топика попадёт в лог).
5. `OUTBOX_ENABLED=false` (в `.env` или окружении сервиса) и перезапуск: переводы работают, события копятся в `outbox_events`; после возврата к `true` уходят все накопленные.

Повторная доставка одного `event-id` допустима (at-least-once), поэтому потребитель обязан отбрасывать дубли по `eventId` из тела события (так делает notification-service). Отправленные строки `outbox_events` удаляются очисткой по сроку хранения.

## notification-service

Читает события переводов из топика `transfers.events.v1` и создаёт уведомления, затем «отправляет» их через заменяемый канал. Публичного API у сервиса нет: только `/actuator/health`, `/actuator/prometheus` и внутреннее `/internal/**`, порт 8083.

- **Кому и что.** `TransferCompleted`: владельцу счёта зачисления (`toOwnerId`): «Вам поступил перевод {amount} {currency}»; если `toOwnerId` нет (событие старого формата), уведомления нет. `TransferFailed`: инициатору перевода: «Перевод {amount} {currency} не выполнен: {причина}». Отправителю об успешном переводе уведомление не приходит.
- **Дубли.** На одно событие ровно одно уведомление: `UNIQUE (source_event_id)` и `INSERT ... ON CONFLICT DO NOTHING`. Повторная доставка того же `eventId` ничего не меняет.
- **Ошибки.** Битое или неизвестное сообщение (нет заголовка `event-type`, невалидный JSON, нет обязательных полей) сразу уходит в `transfers.events.v1.DLT`. Остальные ошибки (например, недоступна БД) повторяются: 10 повторов с паузами 1, 2, 4, 8, 16 с и пять по 30 с (всего 181 с плюс время попыток), затем сообщение тоже уходит в DLT. Пока идут повторы, партиция стоит: так сохраняется порядок событий одного перевода.
- **Отправка.** Dispatcher раз в секунду захватывает до 100 уведомлений (`NEW`, срок которых наступил, и `SENDING` с истёкшей арендой), ставит им `SENDING` на `lease` (2 мин), вызывает `NotificationChannel` вне транзакции и записывает итог каждого: `SENT` (и `sent_at`) либо повтор. Канал по умолчанию пишет строку в лог. Доставка at-least-once: после падения посреди отправки уведомление может уйти повторно.
- **Повторы отправки.** Всего 5 попыток с паузами 10, 20, 40 и 80 с, затем `FAILED`; причина в `last_error`. Параметры: `app.dispatch.retry.*`.
- **Выключатели** (переменные окружения; параметры повторов, интервала и размера партии лежат в `application.yml`):

| Переменная | По умолчанию | Что делает |
|---|---|---|
| `CONSUMER_ENABLED` | `true` | `false`: слушатель и создание топика DLT выключены, события остаются в Kafka и обработаются после включения |
| `DISPATCH_ENABLED` | `true` | `false`: уведомления копятся в `NEW` и уйдут после включения |
| `INTERNAL_API_TOKEN` | `internal_dev_token` | секрет заголовка `X-Internal-Token` для `/internal/**`, тот же, что у других сервисов; пустое значение останавливает старт |

При запуске из IDE notification-service не читает `.env`: свои значения задавайте переменными окружения.

### Ручная повторная отправка

| Метод и путь | Назначение | Ответы |
|---|---|---|
| `POST /internal/notifications/{id}/retry` | вернуть `FAILED` в очередь | 200 `{id, status}`; 404 `NOTIFICATION_NOT_FOUND`; 409 `NOTIFICATION_NOT_FAILED` |
| `POST /internal/notifications/retry-failed?limit=100` | вернуть до `limit` (1..1000) самых старых `FAILED` | 200 `{requeued}`; 400 `INVALID_LIMIT` |

Нужен заголовок `X-Internal-Token`, без него 401 `UNAUTHORIZED`. Счётчик попыток начинается заново, отправку выполняет ближайший проход dispatcher.

### Метрики notification-service

`GET /actuator/prometheus`:

| Метрика (имя в Prometheus) | Что показывает |
|---|---|
| `notification_consumer_dlt_total{reason="parse"}` | дефектные сообщения, ушедшие в DLT |
| `notification_consumer_dlt_total{reason="exhausted"}` | остальные ушедшие в DLT, обычно после повторов |
| `notification_dispatch_result_total{status="sent"}` | отправленные уведомления |
| `notification_dispatch_result_total{status="failed"}` | окончательно неотправленные (`FAILED`) |

Об отправке в DLT пишется ERROR с топиком, партицией, смещением, ключом и классом исключения, без тела сообщения.

### Ручная проверка notification-service

Нужны Postgres, Kafka, account-service и transfer-service, запущенные как в предыдущих чек-листах (`docker compose --profile apps up -d --build`), и один перевод. Команды для Compose:

```bash
# уведомления: после перевода появляется строка, через секунду статус SENT и заполненный sent_at
docker compose exec postgres psql -U postgres -d notification_db \
  -c "select type, message, status, created_at, sent_at from notifications order by created_at desc limit 5"

# строка «отправки» в логе
docker compose logs --tail 20 notification-service

# что попало в DLT: сообщение без заголовков (ключ:значение), потребитель отправит его в DLT без повторов
echo 'bad-key:не json' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic transfers.events.v1 --property parse.key=true --property key.separator=:
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic transfers.events.v1.DLT --from-beginning --timeout-ms 10000 \
  --property print.key=true --property print.headers=true
```

1. Успешный перевод: в `notifications` одна строка `TRANSFER_COMPLETED` для владельца счёта получателя, текст «Вам поступил перевод …», статус `SENT`; в логе сервиса строка с получателем и текстом.
2. Перевод с суммой больше баланса: строка `TRANSFER_FAILED` для инициатора, «… не выполнен: недостаточно средств».
3. Повтор: запрос с тем же `Idempotency-Key` и повторное чтение топика (`--from-beginning`) новых уведомлений не создают; число строк равно числу событий. Дубль можно получить и вручную: сбросить смещения группы (`kafka-consumer-groups.sh --group notification-service --reset-offsets --to-earliest --topic transfers.events.v1 --execute` при остановленном notification-service) и запустить сервис снова: строк не прибавится.
4. Битое сообщение (команда выше): оно появляется в `transfers.events.v1.DLT`, в логе сервиса видна запись об ошибке, обработка следующих событий продолжается.
5. Остановка и запуск: `docker compose stop notification-service`, сделайте перевод, затем `docker compose start notification-service`: уведомление появится после старта (события за простой читаются с сохранённого смещения).
6. `CONSUMER_ENABLED=false` (в `.env`) и пересоздание контейнера (`docker compose --profile apps up -d notification-service`): сервис живёт, но новых уведомлений нет; после возврата к `true` создаются все накопленные. Так же `DISPATCH_ENABLED=false`: строки остаются в `NEW`.
7. Повторная отправка: заглушка канала не отказывает, поэтому переведите строку в `FAILED` вручную и верните через API (токен тот, что в `.env`, по умолчанию `internal_dev_token`):

```bash
ID=$(docker compose exec -T postgres psql -U postgres -d notification_db -Atc \
  "select id from notifications order by created_at desc limit 1")
docker compose exec postgres psql -U postgres -d notification_db \
  -c "update notifications set status = 'FAILED', sent_at = null where id = '$ID'"
curl -i -X POST localhost:8083/internal/notifications/$ID/retry -H 'X-Internal-Token: internal_dev_token'
```

   Ответ 200 и `NEW`, через секунду статус снова `SENT`; повторный вызов даёт 409, вызов без заголовка 401.
8. Метрики: после битого сообщения из пункта 4 `curl -s localhost:8083/actuator/prometheus | grep notification_` показывает рост `reason="parse"`.

Известные ограничения: канал это заглушка; реальному провайдеру понадобится защита от повторной отправки; сообщения в DLT разбираются вручную. Причины решений: `docs/decisions.md`, пункты 46–60.

## events-contract

Модуль без зависимостей и логики: записи событий (`TransferCompletedEvent`, `TransferFailedEvent`),
имена топиков (`Topics`) и типы событий (`EventTypes`). Его подключают сервис-отправитель и сервис-получатель,
поэтому они всегда одинаково понимают формат сообщения.
`TransferCompletedEvent` содержит необязательное поле `toOwnerId` (владелец счёта получателя, чтобы его можно было уведомить); `TransferFailedEvent` владельцев не содержит, потому что account-service при отказе их не возвращает. Уведомление об отказе получает инициатор (`initiatorId`).

Правила развития контракта:
- добавлять только необязательные поля, существующие не переименовывать и не удалять;
- несовместимое изменение — это новый топик (`transfers.events.v2`) и параллельная поддержка старого;
- в модуль не добавлять Spring, JPA и бизнес-логику.

## Структура БД и миграции

Миграции Flyway лежат в каждом сервисе: `<service>/src/main/resources/db/migration/`.
Схемы баз создаёт скрипт `docker/postgres-init/00_init_databases.sh` (выполняется один раз на пустом томе).
Внешних ключей между базами нет, согласованность обеспечивает приложение.

## Статус

- [x] Каркас: Maven-модули, Dockerfile, Compose, миграции, проверка контекста на Testcontainers
- [x] account-service: счета, пополнение (идемпотентное), история, внутренний перевод, защита `/internal` общим секретом
- [x] JWT-аутентификация в account-service: регистрация, вход, RS256, владение счетами, `initiatorId` во внутреннем переводе
- [x] transfer-service: JWT по публичному ключу, идемпотентные переводы, вызов account-service с таймаутами и повтором, `GET /transfers/{id}`, фоновое добивание `PENDING`
- [x] transfer-service: outbox (событие в транзакции финализации), публикация в Kafka (`transfers.events.v1`), тесты на Testcontainers с Kafka
- [x] notification-service: потребитель событий, защита от дублей (`UNIQUE (source_event_id)`), повторы и DLT, dispatcher с заменяемым каналом, тесты на Testcontainers с Kafka
- [x] Доработки надёжности: видимость и повторы в notification-service, «парковка» и очистка outbox, предел добивания `PENDING`, точность суммы, `GET /accounts` (решения 57–68)
- [x] Ручная проверка доработок по чек-листам выше

Возможные дальнейшие улучшения: лимиты переводов на Redis, дашборд метрик и нагрузочный тест, кэш баланса, двойная запись (ledger), возврат перевода и комиссии, выписка с фильтрами, конвертация валют, холд и подтверждение, Kubernetes.

Принятые решения: [docs/decisions.md](docs/decisions.md)
