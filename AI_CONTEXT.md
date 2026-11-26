# AI_CONTEXT — контекст проекта transfers-springboot

Актуальность: на 2026-11-25, «все три сервиса реализованы: account-service, transfer-service (JWT, переводы с идемпотентностью, добивание `PENDING`, outbox и публикация в Kafka) и notification-service (потребитель `transfers.events.v1`, дедупликация, повторы и DLT, dispatcher); `mvn verify` зелёный после каждого этапа, ручная проверка notification-service по чек-листу README ещё не пройдена». После каждого этапа обновляйте разделы «Статус» и «Следующие шаги».

---

## 1. Правила работы для ИИ

- Отвечать **по-русски**, по делу; объяснять «зачем», а не только «что». Не раздувать ответ.
- Не добавлять инструменты и зависимости, которые не обсуждались; не расширять скоуп без запроса. Минимальный вариант сначала, расширения отдельно.
- Версии библиотек и теги образов **проверять**, а не брать по памяти.
- Честно говорить, что не проверено (сборка, запуск, тесты). Не утверждать «работает», если не запускалось.
- Файлы отдавать целиком, с указанием, в какую папку класть. Для нескольких файлов — архив только с новыми и изменёнными файлами; в ответе перечислять, какие файлы заменяют прежние. Архивы применяются по порядку.
- Файлы, которых ИИ не видел (`docker-compose.yml`, `.env.example`, `Dockerfile`, `ci.yml`, init-скрипт), не переписывать с нуля: просить прислать или давать точечные правки.
- Код: Java 21, DTO как `record`, пакеты по фичам, деньги только `BigDecimal`/`NUMERIC`, идентификаторы `UUID`, время `TIMESTAMPTZ`/`Instant`. Lombok не используется. Сущности наружу через API не отдавать.
- Тесты писать вместе с каждым этапом, чтобы `mvn verify` оставался зелёным: интеграционные — на Testcontainers с реальными зависимостями (Postgres и Kafka; наследуют `IntegrationTestBase`, тесты с брокером наследуют `KafkaIntegrationTestBase`), быстрые unit-тесты — где контекст не нужен.
- Решения, которые меняют уже принятые, предлагать отдельно, а не вносить молча.

## 2. Технологии и версии

| Что | Значение |
|---|---|
| Язык / сборка | Java 21, Maven (multi-module), groupId `io.moneyflow`, корневой artifactId `transfers-springboot`, версия `0.1.0-SNAPSHOT` |
| Spring Boot | **3.5.16** — последний релиз ветки 3.5 в open source; переход на 4.x — отдельная задача |
| БД | PostgreSQL 17 (`postgres:17`), миграции Flyway (`flyway-core` + `flyway-database-postgresql`) |
| Пул соединений | HikariCP (автоконфигурация Boot), `maximum-pool-size: 10` задан явно, `lock_timeout = 5s` через `connection-init-sql` |
| Брокер | Apache Kafka 4.1.1 (`apache/kafka:4.1.1`, KRaft, один узел) |
| Kafka-клиент / тесты | `spring-kafka` (версия Boot 3.5.16), продюсер `acks=all`, идемпотентный; тесты: `org.testcontainers:kafka`, `org.testcontainers.kafka.KafkaContainer`, образ `apache/kafka:4.1.1` |
| Кэш | Redis 8 (`redis:8.10-alpine`): контейнер в Compose есть, **transfer-service его не использует** (убран из `pom.xml` и `application.yml`); вернётся для лимитов переводов на пользователя после outbox и Kafka |
| Образы приложений | сборка `maven:3.9-eclipse-temurin-21`, запуск `eclipse-temurin:21-jre` |
| Тесты | JUnit 5, Testcontainers (через `spring-boot-testcontainers`, `@ServiceConnection`) |
| Метрики | Actuator + Micrometer Prometheus; включены виртуальные потоки (`spring.threads.virtual.enabled=true`) |

Репозиторий: https://github.com/AntonSpas/transfers-springboot

## 3. Архитектура

```
Клиент ──JWT──▶ transfer-service ──REST──▶ account-service
                     │ (transfers, outbox)      (users, accounts, проводки, выдача JWT)
                     └──Kafka: transfers.events.v1──▶ notification-service
```

| Модуль | Назначение | БД | Порт IDE / хост Docker |
|---|---|---|---|
| `account-service` | пользователи, счета, проводки, аудит, выдача JWT | `account_db` | 8081 / 8081 |
| `transfer-service` | приём переводов, идемпотентность, оркестрация, outbox, публикация событий | `transfer_db` | 8082 / 8082 |
| `notification-service` | потребление событий, уведомления | `notification_db` | 8083 / 8083 |
| `events-contract` | records событий + константы топиков/типов, без зависимостей и логики | — | — |

Внутри контейнеров все сервисы слушают 8080 (`SERVER_PORT=8080` задан в Dockerfile); межсервисные вызовы идут на `http://account-service:8080`.

### Роли сервисов

- **account-service** — владелец балансов и проводок: атомарно выполняет списание и зачисление, проверяет баланс и владельца счёта. О Kafka не знает.
- **transfer-service** — оркестратор: публичное API переводов, идемпотентность клиента, статус перевода, вызов account-service с таймаутами и повтором, фоновое добивание зависших `PENDING` (реализовано), outbox и публикация событий в Kafka (реализовано). Здесь же будут жить лимиты (в том числе на Redis), холды, проверки (опциональные расширения).
- **notification-service** — потребитель событий: по каждому событию ровно одно уведомление (дубли отсекает `UNIQUE (source_event_id)`), затем dispatcher «отправляет» его через заменяемый канал (заглушка с логом); необработанные сообщения после повторов уходят в DLT `transfers.events.v1.DLT`.

Порядок одного перевода: проверить JWT → вставить `transfer` в статусе `PENDING` (при дубле ключа вернуть существующий) → вызвать account-service (вне транзакции БД) → в короткой транзакции выставить `COMPLETED`/`FAILED` и в той же транзакции записать событие в `outbox_events` (только при реальном переходе); отдельный проход publisher отправляет его в Kafka. Сбой между вызовом и записью статуса закрывается повтором того же `transferId`: account-service идемпотентен.

### Ключевые решения

(подробности и причины — в `docs/decisions.md`)

1. Один контейнер Postgres, **три отдельные базы**; у каждого сервиса своя роль, `CONNECT` у PUBLIC отозван — сервис видит только свою базу. Проверено на тестовом PostgreSQL.
2. Flyway-миграции **в каждом сервисе** (`src/main/resources/db/migration`). Hibernate: `ddl-auto: validate`.
3. Внешних ключей между базами нет; чужие идентификаторы — обычные UUID.
4. Перевод выполняется **одной локальной транзакцией в account-service** (`POST /internal/transfers`), чтобы не нужны были распределённые транзакции. transfer-service оркестрирует: создаёт `transfer` (PENDING), вызывает account-service, ставит COMPLETED/FAILED, пишет событие в outbox.
5. **Идемпотентность на четырёх уровнях:**
   - клиент → transfer-service: `UNIQUE (initiator_id, idempotency_key)` в `transfers` (+ обработка дубля в коде, сравнение параметров запроса);
   - transfer-service → account-service: `UNIQUE (transfer_id, type)` в `account_transactions`;
   - клиент → account-service (пополнения): обязательный `Idempotency-Key`, уникальный индекс `(account_id, idempotency_key)`;
   - Kafka → notification-service: `UNIQUE (source_event_id)` в `notifications`.
6. **Transactional outbox:** событие пишется в `outbox_events` в той же транзакции, что и перевод; отдельный publisher (`@Scheduled`, `FOR UPDATE SKIP LOCKED`) отправляет в Kafka и ставит `published_at`. Доставка at-least-once, консьюмер идемпотентен. Реализовано в transfer-service (решения 38–45 в `docs/decisions.md`).
7. **Защита от гонок:** основная — пессимистичная блокировка `SELECT ... FOR UPDATE`; `@Version` — страховка от забытой блокировки; перевод блокирует оба счёта одним запросом в порядке `id` (нет deadlock встречных переводов); `CHECK (balance >= 0)` — последний рубеж. Проверка повтора идемпотентной операции делается **после** блокировки счёта.
8. `account_transactions` и `audit_log` неизменяемы (триггеры запрещают UPDATE/DELETE); исправления — новыми записями.
9. **События:** один топик `transfers.events.v1`, тип события в заголовке Kafka `event-type` (`TransferCompleted`, `TransferFailed`). Правила контракта: только добавлять необязательные поля; несовместимое изменение — новый топик `v2`. `TransferCompleted` содержит необязательное `toOwnerId` (получатель), `TransferFailed` владельцев не содержит. Заголовки: `event-type` (контракт) и `event-id` (= `event_id`, константа в `OutboxPublisher`). Ключ сообщения — id перевода.
10. Двойная запись (ledger), холды, лимиты, конвертация валют — **опциональные расширения**, в базовую версию не входят.
11. **Временные сбои конкуренции** (таймаут блокировки, deadlock, конфликт версий) отдаются как **503 + `Retry-After: 1`**, коды `RESOURCE_BUSY` / `CONCURRENT_MODIFICATION`. Клиентское правило: 503 = повтори с тем же ключом. 409 остаётся только за конфликтом параметров (`TRANSFER_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`), он повтором не лечится.
12. **Защита `/internal/**` — общий секрет** в заголовке `X-Internal-Token` (переменная `INTERNAL_API_TOKEN`), отдельная цепочка безопасности; пользовательский JWT доступа к `/internal` не даёт. Путь развития — сервисный JWT.
13. **transfer-service** (решения 29–37 в `docs/decisions.md`): JWT только по публичному ключу; явные короткие транзакции и вызов account-service вне транзакции; 4xx account-service 401/400/409 это 502 и `PENDING`, а не `FAILED`; идемпотентность клиента на уникальном индексе с перехватом нарушения; 404 раньше 403 и без обхода для ADMIN; фоновое добивание с `SKIP LOCKED` и флагом; Redis убран, вернётся для лимитов.
14. **transfer-service: outbox и Kafka** (решения 38–45): событие пишется в транзакции финализации и только при реальном переходе (одно событие на перевод); `toOwnerId` добавлен в `TransferCompleted`; publisher выбирает и отправляет партию в одной транзакции с `SKIP LOCKED` (осознанное исключение из правила «внешний вызов вне транзакции»), при первой ошибке проход прекращается; продюсер с ограниченными таймаутами; флаг `OUTBOX_ENABLED`; DLQ и очистки опубликованных строк нет.
15. **notification-service** (решения 46–56): получатель успеха — только `toOwnerId` (при `null` уведомления нет), неуспеха — `initiatorId`; дедупликация нативным `INSERT ... ON CONFLICT (source_event_id) DO NOTHING` (`processed_events` не используется); одно сообщение — одна транзакция, `ack-mode=RECORD`; тип события по заголовку `event-type`, String-десериализаторы; `EventParseException` и `NullPointerException` сразу в DLT, остальное — 3 повтора (1 с × 2, максимум 10 с) и DLT; блокирующие повторы (партиция стоит ради порядка); dispatcher отправляет внутри транзакции под `SKIP LOCKED` (как решение 40, для реального канала пересмотреть), `FAILED` терминальный; выключатели `CONSUMER_ENABLED` и `DISPATCH_ENABLED`.

### Формат ошибок account-service

Все ошибки: `{ timestamp, status, code, message, violations[] }` (`ApiError`; `violations` только при `VALIDATION_FAILED`). Стандартные ошибки Spring MVC (битый JSON, неверный UUID, нет заголовка) остаются 4xx с кодом `BAD_REQUEST`. Непредвиденное — 500 `INTERNAL_ERROR` без деталей наружу.

### JWT (реализовано в account-service)

- Выдача: `POST /auth/register` (201) и `POST /auth/login` (200) возвращают `{userId, accessToken, tokenType, expiresIn}`. Пароли — BCrypt, 8–72 символа и ≤72 байт UTF-8; одинаковый 401 `INVALID_CREDENTIALS` для неизвестного email и неверного пароля (с фиктивным хэшем для выравнивания времени); дубль email — 409 `EMAIL_ALREADY_REGISTERED`.
- Токен: RS256, 15 минут (`app.jwt.access-token-ttl`), claims `sub` = User.id, `roles` (→ `ROLE_*`), `iat`, `exp`. Refresh-токенов и JWKS нет. Декодер закреплён за RS256.
- Ключи: PEM (закрытый PKCS#8, открытый X.509, RSA ≥2048, пара должна совпадать) по путям `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH`, без значений по умолчанию, fail-fast при старте. Файловый `JwtKeyPair` — `@Lazy`; в тестах подменяется парой в памяти (`JwtTestConfig`, `@Primary`). `scripts/gen-keys.sh` → `keys/` (в `.gitignore`). Compose монтирует `./keys:/keys:ro` в account-service и задаёт пути `/keys/...`. Для IDE/Maven пути лежат в `.env` (абсолютные, прямые слэши; Spring читает его через `spring.config.import`).
- transfer-service проверяет токен по **публичному** ключу (`spring-boot-starter-oauth2-resource-server`, `NimbusJwtDecoder.withPublicKey`, RS256, `JWT_PUBLIC_KEY_PATH` без значения по умолчанию); закрытый ключ только у account-service. В Compose transfer-service монтируется один файл `./keys/public.pem:/keys/public.pem:ro`.
- Безопасность: две цепочки — `InternalSecurityConfig` (`/internal/**`, `@Order(1)`) и `SecurityConfig` (`@Order(2)`, `oauth2ResourceServer`); `ApiErrorSecurityHandler` отдаёт 401 `UNAUTHORIZED` (+`WWW-Authenticate: Bearer`) и 403 `FORBIDDEN` как `ApiError`. `/auth/register` и `/auth/login` игнорируют заголовок `Authorization`.
- Владение: владелец = `sub` (`CurrentUser`); чужой счёт — 403, несуществующий — 404 (существование проверяется первым); роль ADMIN обхода не даёт. Пополнение — только владелец, проверка под `FOR UPDATE` до идемпотентного повтора. Внутренний перевод: `initiatorId` проверяется сразу после блокировки счетов (до поиска получателя, валюты и повтора). Ответ перевода содержит `fromOwnerId`, `toOwnerId`.
- Аудит: `actor_id` = пользователь/инициатор, NULL только у системных действий.

## 4. Минимальный набор операций (план)

account-service: создать счёт; пополнить; получить баланс; история операций (пагинация); внутренний атомарный перевод. **Реализовано.**
transfer-service: `POST /transfers` (заголовок `Idempotency-Key`); `GET /transfers/{id}`.
notification-service: потребление `TransferCompleted/Failed`, запись уведомления (реализовано), отправка через канал (реализовано).
Валидации и ошибки: недостаточно средств, счёт не найден, разные валюты, сумма ≤ 0; единый формат ошибок (`@RestControllerAdvice`).

### API account-service (реализовано)

Все, кроме `/auth/*`, `/actuator/health/**`, `/actuator/prometheus` и `/internal/**`, требуют `Authorization: Bearer <JWT>`; без него — 401 `UNAUTHORIZED`.

| Метод и путь | Назначение | Ответы |
|---|---|---|
| `POST /auth/register` `{email, password}` | регистрация + токен | 201; 409 `EMAIL_ALREADY_REGISTERED`; 400 `VALIDATION_FAILED`, `INVALID_PASSWORD` |
| `POST /auth/login` `{email, password}` | вход | 200; 401 `INVALID_CREDENTIALS`; 400 |
| `POST /accounts` `{currency}` | создать счёт текущего пользователя | 201 + `Location`; 422 `CURRENCY_NOT_SUPPORTED`; 400 `VALIDATION_FAILED`; 401 |
| `GET /accounts/{id}` | счёт с балансом | 200; 403 `FORBIDDEN` (чужой); 404 `ACCOUNT_NOT_FOUND` |
| `GET /accounts/{id}/balance` | баланс | 200; 403; 404 |
| `POST /accounts/{id}/deposits` `{amount}` + заголовок `Idempotency-Key` (обязателен, ≤100 символов) | пополнить (только владелец) | 201 (выполнено), 200 (повтор того же ключа и суммы); 409 `IDEMPOTENCY_KEY_REUSED`; 400 `INVALID_IDEMPOTENCY_KEY`, `VALIDATION_FAILED`; 403; 404 |
| `GET /accounts/{id}/transactions?page&size` | история (только владелец), новые сверху | 200 `PageResponse` (`size` 1..100, по умолчанию 20; `sort` клиента игнорируется); 403; 404 |
| `POST /internal/transfers` `{transferId, initiatorId, fromAccountId, toAccountId, amount, currency}` + `X-Internal-Token` | атомарный перевод | 201 (выполнено), 200 (повтор того же `transferId`), тело с `fromOwnerId`/`toOwnerId`; 409 `TRANSFER_CONFLICT`; 403 `FORBIDDEN` (счёт списания не принадлежит `initiatorId`); 422 `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`; 404; 401 `UNAUTHORIZED` |

Сумма: больше 0, до 15 цифр в целой части и до 4 в дробной (лишние знаки отклоняются, не округляются). Любая операция может вернуть 503 (см. решение 11).

### transfer-service: API и поведение (реализовано)

| Метод и путь | Назначение | Ответы |
|---|---|---|
| `POST /transfers` `{fromAccountId, toAccountId, amount, currency}` + `Idempotency-Key` (обязателен, ≤100 символов) | создать перевод; инициатор = `sub` | 201 (создан и обработан: `COMPLETED` или `FAILED` с `failureReason`), 200 (повтор того же ключа и параметров), 202 (`PENDING`, account-service недоступен), 409 `IDEMPOTENCY_KEY_REUSED`, 422 `SAME_ACCOUNT`, 400 `INVALID_IDEMPOTENCY_KEY` / `VALIDATION_FAILED`, 502 `UPSTREAM_ERROR`, 401 |
| `GET /transfers/{id}` | перевод (только инициатору) | 200; 404 `TRANSFER_NOT_FOUND` (проверяется первым); 403 `FORBIDDEN` (чужой, ADMIN обхода не даёт); 401 |

Тело ответа: `{id, fromAccountId, toAccountId, amount, currency, status, failureReason, createdAt, updatedAt}`; `id` = `transferId` для account-service. Сумма как в account-service (>0, до 15+4 знаков), валюта `^[A-Z]{3}$`.

Исходы вызова account-service (решения 32–33 в `docs/decisions.md`): 201/200 → `COMPLETED`; 404/403/422 → `FAILED` с кодом в `failure_reason`; 503/5xx/сеть/таймаут (после 3 попыток с тем же `transferId`, таймауты 2 с / 5 с) → остаётся `PENDING`, клиенту 202; 401/400/409/неизвестный 4xx → остаётся `PENDING`, клиенту 502, статус `FAILED` не ставится. Повтор запроса для `PENDING` заново вызывает account-service с тем же `transferId`. Параллельные запросы одного ключа внутри экземпляра дают один вызов (`KeyedLock`); между экземплярами дубль безвреден.

Фоновое добивание (`PendingTransferRetrier`, `PendingRetryScheduling`): `app.pending-retry.enabled` (env `PENDING_RETRY_ENABLED`, по умолчанию `true`), `interval: 30s`, `min-age: 60s`, `batch-size: 50`; выбор партии `FOR UPDATE SKIP LOCKED` в короткой транзакции, обработка вне транзакции; при недоступности account-service проход прекращается; перевод с постоянным 502 вытесняет новые из партии (известное ограничение). В общем тестовом контексте планировщик выключен, логика проверяется вызовом `runOnce()`.

### transfer-service: outbox и публикация в Kafka (реализовано)

- Запись: `TransferProcessor.finalizeTransfer` вызывает `OutboxWriter` (`Propagation.MANDATORY`) только при `applied == true`: `writeTransferCompleted(transfer, toOwnerId, now)` (владелец получателя из ответа account-service) или `writeTransferFailed(transfer, now)`. Конструктор процессора: `(TransferRepository, AccountServiceClient, OutboxWriter, PlatformTransactionManager, Clock)`. `OutboxEvent` (`Persistable`, `payload` JSONB строкой), `OutboxRepository.lockUnpublished(limit)` (native, `SKIP LOCKED`, `MANDATORY`).
- Публикация: `OutboxPublisher.runOnce()` возвращает `Report(selected, published, stoppedEarly)`; одна транзакция: выборка, отправка по порядку с ожиданием `send-timeout`, `markPublished`; при первой ошибке остановка. `OutboxScheduling` (флаг `app.outbox.enabled`, пауза от конца прохода), `OutboxTopicConfig` (`NewTopic` `transfers.events.v1`, 3 партиции, 1 реплика, только при включённой публикации), `OutboxProperties` (`enabled` / `OUTBOX_ENABLED` = `true`, `interval` 1s, `batch-size` 100, не больше 1000, `send-timeout` 5s) и `OutboxConfig`.
- Продюсер: `spring.kafka.producer.*` в `application.yml` (`acks=all`, идемпотентность, `linger.ms=5`, `request.timeout.ms=3000`, `delivery.timeout.ms=5000`, `max.block.ms=3000`).
- Сообщение: ключ = `aggregate_id`, значение = JSON события, заголовки `event-type` и `event-id`.

## 5. Структура БД

Миграции V1, V2 применялись на PostgreSQL 16 с проверкой ограничений; V3 проверяется интеграционными тестами на `postgres:17`.

**account_db** (V1, V2, V3):
- `currencies(code PK, name, minor_units)` — сидированы RUB, USD, EUR.
- `users(id UUID PK, email, password_hash, roles TEXT[], created_at, updated_at)`; уникальный индекс по `lower(email)`.
- `accounts(id, owner_id → users, currency → currencies, balance NUMERIC(19,4) CHECK >= 0, version, created_at, updated_at)`; индекс по `owner_id`. Статусов у счёта нет. Ограничения «один счёт на валюту» нет.
- `account_transactions(id, account_id → accounts, type IN (DEPOSIT, TRANSFER_DEBIT, TRANSFER_CREDIT), amount CHECK > 0, balance_after CHECK >= 0, transfer_id NULL, idempotency_key VARCHAR(100) NULL, created_at)`; `UNIQUE (transfer_id, type)`; CHECK: для переводов `transfer_id` обязателен, для пополнений должен быть NULL; **V3:** частичный уникальный индекс `uq_acc_tx_account_idem (account_id, idempotency_key) WHERE idempotency_key IS NOT NULL`, CHECK `idempotency_key` только у `DEPOSIT` и не пустой (у пополнений до V3 ключа нет); индекс `(account_id, created_at DESC, id DESC)`.
- `audit_log(id identity, actor_id NULL, action, entity_type, entity_id, details JSONB, created_at)`; индексы по сущности и актору. Действия: `CREATE_ACCOUNT`, `DEPOSIT`, `TRANSFER`, а также регистрация и вход; `actor_id` — пользователь (для перевода `initiatorId`), NULL только у системных действий.
- Триггеры `forbid_modification` на `account_transactions` и `audit_log`.

**transfer_db** (V1):
- `transfers(id UUID PK = transfer_id, idempotency_key, initiator_id, from_account_id, to_account_id, amount CHECK > 0, currency, status IN (PENDING, COMPLETED, FAILED), failure_reason, created_at, updated_at)`; `UNIQUE (initiator_id, idempotency_key)`; CHECK `from <> to`; индексы по счетам/инициатору и частичный по `status = 'PENDING'`.
- `outbox_events(id UUID PK = event_id, aggregate_id, type, payload JSONB, created_at, published_at)`; частичный индекс по неотправленным.

**notification_db** (V1):
- `notifications(id, user_id, type IN (TRANSFER_COMPLETED, TRANSFER_FAILED), message, source_event_id UNIQUE, status IN (NEW, SENT, FAILED), created_at, sent_at)`; индексы `(user_id, created_at DESC)` и частичный по `NEW`.
- `processed_events(event_id PK, processed_at)` — опциональный inbox (уникальность по `source_event_id` уже защищает от дублей).

Схемы баз, роли и права создаёт `docker/postgres-init/00_init_databases.sh` (выполняется один раз при первом создании тома Postgres).

## 6. Структура репозитория

```
transfers-springboot/
├── pom.xml                         # родительский, Boot 3.5.16, модули
├── Dockerfile                      # один на все сервисы, ARG MODULE
├── docker-compose.yml              # инфраструктура + сервисы (профиль apps)
├── .env.example  .gitignore  .dockerignore  .gitattributes
├── AI_CONTEXT.md  README.md  docs/decisions.md
├── .github/workflows/ci.yml        # mvn -B verify
├── docker/postgres-init/00_init_databases.sh
├── scripts/gen-keys.sh
├── events-contract/                # Topics, EventTypes, TransferCompletedEvent, TransferFailedEvent
├── account-service/                # см. ниже
├── transfer-service/               # io.moneyflow.transfer: common, config, client, transfer, outbox; db/migration V1
└── notification-service/           # io.moneyflow.notification: config, notification, consumer, dispatch; db/migration V1
```

Пакеты по фичам. **account-service** (`io.moneyflow.account.*`):
- `auth` — `User`, `UserRepository`, `AuthController`, `AuthService`, `JwtTokenService`, `CurrentUser`, DTO (`RegisterRequest`, `LoginRequest`, `AuthResponse`, `IssuedToken`), исключения;
- `account` — `Account`, `AccountService`, `AccountController`, `Currency`, DTO (`CreateAccountRequest{currency}`, `DepositRequest`, `AccountResponse`, `BalanceResponse`, `DepositResult`), исключения;
- `transaction` — `AccountTransaction` (`@Immutable`, создаётся фабриками), `TransactionType`, репозиторий, `TransactionService`, `TransactionController`, `TransactionResponse`;
- `transfer` — `TransferService`, `InternalTransferController`, DTO и исключения внутреннего перевода;
- `audit` — `AuditLog`, `AuditService` (`Propagation.MANDATORY`), `AuditActions`;
- `common` — `ApiError`, `ApiException`, `GlobalExceptionHandler`, `PageResponse`;
- `config` — `SecurityConfig` (публичное API, `@Order(2)`), `InternalSecurityConfig` (`/internal/**`, `@Order(1)`), `InternalApiTokenFilter`, `InternalApiProperties`, `ApiErrorSecurityHandler`, `JwtProperties`, `JwtKeyPair`, `JwtKeyLoader`, `JwtConfig`, `PasswordEncoderConfig`.

**transfer-service** (`io.moneyflow.transfer.*`):
- `common` — `ApiError`, `ApiException`, `GlobalExceptionHandler`, `CurrentUser` (UUID из `sub`), `UnauthorizedException`;
- `config` — `SecurityConfig`, `ApiErrorSecurityHandler`, `JwtProperties`, `JwtPublicKeyLoader`, `JwtKeyException`, `JwtConfig` (файловые бины `@Lazy`), `ClockConfig`;
- `client` — `AccountClientProperties`, `AccountClientConfig` (`RestClient`), `AccountServiceClient`, `AccountCallResult` (sealed: `Completed`/`Rejected`/`Unavailable`/`UpstreamError`), `InternalTransferRequest`/`InternalTransferResult` (дубли DTO account-service);
- `transfer` — `Transfer` (`Persistable`), `TransferStatus`, `TransferRepository` (`findByIdForUpdate`, `lockStalePending`), `TransferProcessor` (транзакции через `TransactionTemplate`), `KeyedLock`, `ProcessingResult`, `TransferService`, `TransferController`, DTO (`CreateTransferRequest`, `TransferResponse`, `CreateTransferResult`), исключения (`SameAccountException`, `InvalidIdempotencyKeyException`, `IdempotencyKeyReusedException`, `UpstreamErrorException`, `TransferNotFoundException`, `TransferAccessDeniedException`), `PendingRetryProperties`, `PendingRetryConfig`, `PendingTransferRetrier`, `PendingRetryScheduling`;
- `outbox` — `OutboxEvent`, `OutboxRepository`, `OutboxWriter`, `OutboxPublisher`, `OutboxScheduling`, `OutboxTopicConfig`, `OutboxProperties`, `OutboxConfig`.

Тесты transfer-service: `support/` (`IntegrationTestBase` с `account-service.retry-backoff=0ms` и выключенным добиванием, `PostgresTestConfig`, `JwtTestConfig`, `TestTokens`, `AccountMock` на `MockRestServiceServer` с `@Primary` `RestClient`, `AccountMockTestConfig`), быстрые тесты (`TransferTest`, `KeyedLockTest`, `TransferServiceTest`, `TransferServiceGetTest`, `PendingRetryPropertiesTest`, `PendingRetrySchedulingTest`, конфигурация JWT и клиента, обработчики ошибок), интеграционные (`TransferRepositoryTest`, `TransferProcessorTest`, `TransferProcessorIntegrationTest`, `TransferCreateApiTest`, `TransferIdempotencyApiTest`, `TransferGetApiTest`, `TransferRaceApiTest`, `PendingTransferRetrierIntegrationTest`, `SecurityConfigTest`, `JwtKeyStartupTest`). Ожидания `MockRestServiceServer` регистрируются до первого запроса. Ожидаемые `ERROR` в логах тестов, провоцирующих ошибки, не подавляются.

Тесты outbox (transfer-service): `outbox/` — `OutboxEventTest`, `OutboxPropertiesTest`, `OutboxSchedulingTest` (быстрые); `OutboxRepositoryIntegrationTest`, `OutboxWriterIntegrationTest`, `OutboxPublisherIntegrationTest` (общий контекст, `KafkaTemplate` подменён Mockito), `OutboxDisabledInSharedContextTest`, `TransferOutboxApiTest` (сквозной `POST /transfers` → `outbox_events`), `OutboxKafkaIntegrationTest` (настоящий брокер); `transfer/TransferProcessorOutboxIntegrationTest` (запись, гонка двух экземпляров, откат). `IntegrationTestBase` выключает публикацию (`app.outbox.enabled=false`); `KafkaIntegrationTestBase` + `KafkaTestConfig` дают единственный дополнительный контекст (свой Postgres и Kafka, планировщик с интервалом в час, проходы вызываются из теста). Тесты `outbox_events` чистят таблицу перед собой или ищут свои события по `aggregate_id`.

Тесты account-service (`src/test`): `support/IntegrationTestBase` (`@Import` `PostgresTestConfig` + `JwtTestConfig`; один контейнер Postgres и один кэшированный контекст на прогон; помощники `bearer`, `expiredBearer`, `ownerBearer`, `ownerOf`); интеграционные — `AuthApiTest`, `AccountApiTest`, `AccountOwnershipTest`, `DepositAndHistoryTest`, `DepositIdempotencyTest`, `InternalTransferTest`, `InternalApiSecurityTest`, `JwtAuthenticationTest`, `SecurityConfigTest`, `PasswordEncoderConfigTest`, `JwtTokenServiceTest`, `LedgerImmutabilityTest`, `LockTimeoutTest` (свой контекст с `lock_timeout = 500ms`, отдельный контейнер); быстрые — `JwtKeyLoaderTest`, `CurrentUserTest`, `ApiErrorSecurityHandlerTest`, `DomainModelTest`, `GlobalExceptionHandlerTest`, `InternalApiTokenFilterTest`. Классы названы `*Test`, а не `*IT`: CI запускает `mvn verify` без Failsafe, `*IT` не выполнились бы. Проводки и аудит не очищаются (триггеры), тесты создают уникальные данные.

**notification-service** (`io.moneyflow.notification.*`):
- `config` — `ClockConfig`;
- `notification` — `Notification` (`Persistable`, без `@ManyToOne`), `NotificationType`, `NotificationStatus`, `NotificationRepository` (`insertIfAbsent` — нативный `ON CONFLICT DO NOTHING`, `lockNew` — `FOR UPDATE SKIP LOCKED`, оба `Propagation.MANDATORY`), `NotificationMessages` (тексты), `NotificationService` (`handle(...)` → `Outcome {CREATED, DUPLICATE, NO_RECIPIENT}`);
- `consumer` — `TransferEventsListener` (`@KafkaListener`, `autoStartup` от `app.consumer.enabled`), `TransferEventParser`, `ParsedEvent`, `EventParseException`, `ErrorClassification`, `ConsumerErrorHandlingConfig` (`DefaultErrorHandler`, `DeadLetterPublishingRecoverer`), `ConsumerTopicConfig` (`NewTopic` для DLT), `ConsumerProperties`, `ConsumerPropertiesConfig`;
- `dispatch` — `NotificationChannel`, `LoggingNotificationChannel`, `NotificationDispatcher` (`runOnce()` → `Report(sent, failed)`), `DispatchScheduling`, `DispatchProperties`, `DispatchPropertiesConfig`.

Тесты notification-service: `support/` (`IntegrationTestBase` с выключенными потребителем и dispatcher, `PostgresTestConfig`; `KafkaIntegrationTestBase` и `KafkaTestConfig` с `apache/kafka:4.1.1` и шпионами `@MockitoSpyBean`; `TestEvents`); быстрые: `NotificationMessagesTest`, `NotificationTest`, `ConsumerPropertiesTest`, `ErrorClassificationTest`, `TransferEventParserTest`, `TransferEventsListenerTest`, `ConsumerErrorHandlingConfigTest`, `DispatchPropertiesTest`, `DispatchSchedulingTest`, `LoggingNotificationChannelTest`; на PostgreSQL: `NotificationServiceApplicationTests`, `NotificationRepositoryTest`, `NotificationServiceTest`, `NotificationServiceClockTest`, `NotificationDispatcherTest`; на брокере: `TransferEventsKafkaIntegrationTest`.

## 7. Конфигурация и запуск

- `application.yml` каждого сервиса (в `src/main/resources/`) читает значения из переменных окружения со значениями по умолчанию для локальной разработки (остаются по решению автора):
  `SERVER_PORT`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`; **`INTERNAL_API_TOKEN`** (по умолчанию `internal_dev_token`; пустое значение останавливает старт) нужен и account-service (проверяет), и transfer-service (отправляет), значение должно совпадать. transfer-service дополнительно: `ACCOUNT_SERVICE_URL` (по умолчанию `http://localhost:8081`, в Compose `http://account-service:8080`), `PENDING_RETRY_ENABLED` (по умолчанию `true`), `OUTBOX_ENABLED` (по умолчанию `true`); `SPRING_DATA_REDIS_*` ему не нужны. notification-service дополнительно: `CONSUMER_ENABLED` и `DISPATCH_ENABLED` (по умолчанию `true`; остальные параметры `app.consumer.*` и `app.dispatch.*` только в `application.yml`). В Compose и `.env.example` переменные есть.
  **`JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH`** — абсолютные пути к PEM-ключам, значений по умолчанию нет (старт без них падает); account-service нужны оба, transfer-service только `JWT_PUBLIC_KEY_PATH`. Для IDE/Maven лежат в `.env` (прямые слэши); `application.yml` подключает `.env` через `spring.config.import: optional:file:.env[.properties]` (и `../.env`). В Docker пути фиксированы в `docker-compose.yml` (`/keys/...`), хостовые из `.env` в контейнер не попадают.
- Пароли по умолчанию: `account_dev_password`, `transfer_dev_password`, `notification_dev_password`; админ Postgres `postgres_dev_password`. Переопределяются через `.env` (`ACCOUNT_DB_PASSWORD` и т. д.). Реальные секреты не коммитить.
- Хостовые порты инфраструктуры: **Postgres 5433** (`POSTGRES_HOST_PORT`, чтобы не конфликтовать с локальным PostgreSQL на 5432), Kafka для IDE `localhost:29092`, внутри сети Docker `kafka:9092`, Redis 6379 (контейнер есть, transfer-service им не пользуется).
- Запуск:
  ```bash
  docker compose up -d                              # postgres, redis, kafka
  mvn verify                                        # нужен запущенный Docker (Testcontainers)
  mvn -pl account-service -am clean verify          # только account-service
  mvn -pl account-service spring-boot:run           # запуск из Maven (порт 8081)
  docker compose --profile apps up -d --build       # + три сервиса
  curl localhost:8081/actuator/health               # и 8082, 8083
  ```
  Сервисы можно запускать из IDE (порты 8081/8082/8083 по умолчанию), инфраструктуру — через Compose.
- Лимиты памяти в Compose: сервисы по 512 МБ, Postgres 384 МБ, Kafka 768 МБ (куча 384 МБ), Redis 160 МБ.
- Скрипты инициализации Postgres выполняются только на пустом томе: после смены паролей — `docker compose down -v`.
- Windows: `.sh`-файлы должны иметь окончания строк LF (защищает `.gitattributes`); в PowerShell `curl` — псевдоним, использовать `curl.exe` или `.http`-файл IDEA.

### Грабли при локальной работе

- **`--profile apps` действует на все сервисы без профиля тоже.** `docker compose --profile apps down` или `stop` затронет и postgres, kafka, redis (при `down` исчезнет и сеть `transfers-springboot_default`; тома остаются). Точечно останавливать приложения — по именам: `docker compose stop account-service transfer-service notification-service` (или `rm -sf` для удаления).
- **Порт хоста 8081 один:** либо сервис из IDE/Maven, либо контейнер. Для одновременной работы запускать IDE-копию с `SERVER_PORT=8091`. Конфликт даёт `ports are not available` в Docker; `SERVER_PORT=8080` в Dockerfile относится только к порту внутри контейнера.
- **`restart: unless-stopped`** поднимает ранее созданные контейнеры (в том числе приложений, профиль не мешает) после перезапуска Docker, и они занимают порты 8081–8083. Для сервисов приложений рекомендовано убрать `restart` или поставить `"no"`.
- Контейнер приложения собран из кода на момент `--build`: после изменений пересобирать.
- **Kafka недоступна при старте transfer-service с включённым outbox:** `KafkaAdmin` может ждать создания топика до 30 с, затем сервис продолжает работу. `OUTBOX_ENABLED=false` убирает это ожидание; события копятся в `outbox_events` и уходят после включения.

## 8. Статус

Сделано:
- [x] Каркас: Maven-модули, три пустых Spring Boot сервиса на нужных стартерах, `events-contract`
- [x] Миграции Flyway во всех трёх сервисах, init-скрипт баз и ролей
- [x] Dockerfile, docker-compose (профили, healthcheck, лимиты), CI на GitHub Actions
- [x] В каждом сервисе тест «контекст поднимается и миграции применяются» на Testcontainers
- [x] Базовый `SecurityConfig` в account/transfer: открыты только `/actuator/health/**` и `/actuator/prometheus`, остальное закрыто
- [x] account-service: сущности и репозитории, создание счёта, баланс, пополнение с идемпотентностью (V3), история с пагинацией, внутренний атомарный перевод с идемпотентностью по `transferId`, аудит, единый формат ошибок, 503 при сбоях блокировок, защита `/internal/**` общим секретом, пул и `lock_timeout` в `application.yml`
- [x] Тесты account-service написаны (в том числе гонки: 50 параллельных списаний, встречные переводы, одинаковый `transferId`/`Idempotency-Key`)

- [x] JWT в account-service (этапы 1–9 нового плана): ключи и загрузка, выдача токена, PasswordEncoder, регистрация/вход, ресурс-сервер с 401/403 в `ApiError`, владение счетами, история, `initiatorId` во внутреннем переводе, Compose/`.env.example`, документация; тесты написаны вместе с этапами

- [x] `mvn verify` зелёный, account-service запускается с ключами из IDE/Maven и в контейнере (подтвердил автор)
- [x] Зелёный `mvn -B verify` в CI после пуша

- [x] transfer-service (этапы 1–12): JWT по публичному ключу, `POST /transfers` с идемпотентностью (201/200/202/409/422/502), `GET /transfers/{id}`, клиент account-service (таймауты, повтор с тем же `transferId`), явные короткие транзакции, фоновое добивание `PENDING`, Compose и документация. Тесты написаны вместе с этапами.
- [x] `mvn verify` зелёный, ручная проверка transfer-service по чек-листу из README пройдена (IDE и контейнер, добив `PENDING` после остановки и запуска account-service) (подтвердил автор)

- [x] transfer-service: outbox и публикация в Kafka (этапы 1–5 задачи outbox): запись события в транзакции финализации, publisher с `SKIP LOCKED`, продюсер с ограниченными таймаутами, флаг `OUTBOX_ENABLED`, Compose и документация. Тесты написаны вместе с этапами.

- [x] `mvn verify` зелёный после задачи outbox, включая тесты на Kafka-контейнере Testcontainers; ручная проверка по чек-листу «Ручная проверка outbox и Kafka» из README пройдена, включая живой планировщик и недоступную Kafka (подтвердил автор)

- [x] notification-service (этапы 1–5): основа и дедупликация, потребитель с повторами и DLT, dispatcher с заменяемым каналом, тесты на брокере Testcontainers, флаги `CONSUMER_ENABLED` и `DISPATCH_ENABLED`, Compose и документация. Тесты написаны вместе с этапами.
- [x] `mvn verify` зелёный после каждого этапа 1–4 notification-service (подтвердил автор).
- [x] Ручная проверка notification-service по чек-листу из README и `mvn verify`

Не сделано: реальный канал отправки уведомлений (email, push), повторная отправка `FAILED`, разбор и повторная обработка сообщений из DLT, лимиты переводов на пользователя на Redis, метрики, нагрузочные тесты, Kubernetes.

## 9. Следующие шаги (по порядку)

1. ~~account-service без аутентификации~~ — реализовано (см. «Статус»).
2. ~~JWT в account-service~~ — реализовано (см. «JWT»);
3. ~~transfer-service~~ — реализовано (см. «Статус» и «transfer-service: API и поведение»).
4. ~~Outbox + Kafka producer в transfer-service~~ — реализовано (см. «transfer-service: outbox и публикация в Kafka»).
5. ~~notification-service~~ — реализовано (см. «Статус» и решения 46–56).

Возможные дальнейшие улучшения:

- Лимиты переводов на пользователя в transfer-service с возвращением Redis (счётчики, rate limiting; решение 29, которое придётся обновить). Правила (что считается, окно, поведение при недоступном Redis) описать до начала.
- Метрики (Micrometer, `/actuator/prometheus`), при желании дашборд в Grafana.
- Нагрузочный тест (k6 или Gatling) с цифрами в README: размер пула и `lock_timeout` — параметры для подбора. Лимиты на время теста отключаются или поднимаются, иначе они ограничат саму нагрузку. После изменений записи (например, ledger) тест перезапускается и цифры обновляются.
- Кэш баланса в Redis для `GET` баланса в account-service: сброс после коммита, сравнение замеров до и после (нужен нагрузочный тест).
- Двойная запись (ledger) в account-service: таблица проводок пишется в той же транзакции, баланс остаётся материализованным столбцом, тест сверки (сумма проводок равна балансу).
- Возврат перевода (`POST /transfers/{id}/reversal`): компенсирующие проводки, новый статус и событие (после ledger).
- Комиссии: отдельная проводка на счёт комиссий (после ledger).
- Выписка за период с фильтрами и описание операции; поиск по описанию средствами Postgres (`tsvector`, GIN).
- Конвертация валют по курсу из таблицы (после ledger).
- Холд и подтверждение (резервирование суммы, затем capture или release).
- Kubernetes (kind): манифесты (Kustomize) лежат рядом с Compose и не заменяют его; две реплики transfer-service показывают безопасность outbox и добивания при нескольких экземплярах. Делается последним, когда набор компонентов окончателен.

### Открытые вопросы

- Ограничивать ли точность суммы по `currencies.minor_units` (сейчас допускается до 4 знаков независимо от валюты).
- Нужен ли `GET /accounts` для списка счетов текущего пользователя (сейчас нет).
- ~~Куда вынести DTO внутреннего API~~ — продублированы в transfer-service (решение 32).
- ~~Нужен ли `GET /internal/transfers/{id}` для добивания `PENDING`~~ — нет, повторный `POST` с тем же `transferId` безопасен и используется.
- Как защитить фоновое добивание от постоянно «отравленных» переводов (стабильный 502 вытесняет новые из партии): сейчас принято как ограничение, возможное решение — счётчик попыток или порядок по времени последней попытки.
- Замена общего секрета на сервисный JWT, если понадобится.
- Блокировка очереди outbox «ядовитым» событием (проход обрывается на первой ошибке, выборка от самых старых): нужен счётчик попыток или DLQ.
- Очистка опубликованных строк `outbox_events` (по `published_at`).
- Вынести имя заголовка `event-id` в `events-contract` (сейчас константа в `OutboxPublisher`): добавление константы не меняет формат событий, но это общий контракт с потребителем. Потребитель заголовок не читает: дедупликация идёт по `eventId` из тела.
- Реальный канал уведомлений: схема «отправка внутри транзакции» (решение 54) для него не подходит, нужен промежуточный статус, таймауты и идемпотентность у провайдера.
- Что делать с `FAILED` уведомлениями и сообщениями в DLT: сейчас только ручной разбор, автоматических повторов нет.
- Неблокирующие retry-топики вместо блокировки партиции (решение 51), если задержки на временных ошибках станут проблемой.
