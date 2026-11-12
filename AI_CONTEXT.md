# AI_CONTEXT — контекст проекта transfers-springboot

Актуальность: на 2026-11-12, этап «account-service реализован (без JWT), ожидается подтверждение прогона тестов». После каждого этапа обновляйте разделы «Статус» и «Следующие шаги».

---

## 1. Правила работы для ИИ

- Отвечать **по-русски**, по делу; объяснять «зачем», а не только «что». Не раздувать ответ.
- Не добавлять инструменты и зависимости, которые не обсуждались; не расширять скоуп без запроса. Минимальный вариант сначала, расширения отдельно.
- Версии библиотек и теги образов **проверять**, а не брать по памяти.
- Честно говорить, что не проверено (сборка, запуск, тесты). Не утверждать «работает», если не запускалось.
- Файлы отдавать целиком, с указанием, в какую папку класть. Для нескольких файлов — архив только с новыми и изменёнными файлами; в ответе перечислять, какие файлы заменяют прежние. Архивы применяются по порядку.
- Файлы, которых ИИ не видел (`docker-compose.yml`, `.env.example`, `Dockerfile`, `ci.yml`, init-скрипт), не переписывать с нуля: просить прислать или давать точечные правки.
- Код: Java 21, DTO как `record`, пакеты по фичам, деньги только `BigDecimal`/`NUMERIC`, идентификаторы `UUID`, время `TIMESTAMPTZ`/`Instant`. Lombok не используется. Сущности наружу через API не отдавать.
- Тесты делать отдельным от основного функционала шагом, по просьбе разработчика (интеграционные — на Testcontainers с реальными Postgres/Kafka/Redis).
- Решения, которые меняют уже принятые, предлагать отдельно, а не вносить молча.

## 2. Технологии и версии

| Что | Значение |
|---|---|
| Язык / сборка | Java 21, Maven (multi-module), groupId `io.moneyflow`, корневой artifactId `transfers-springboot`, версия `0.1.0-SNAPSHOT` |
| Spring Boot | **3.5.16** — последний релиз ветки 3.5 в open source; переход на 4.x — отдельная задача |
| БД | PostgreSQL 17 (`postgres:17`), миграции Flyway (`flyway-core` + `flyway-database-postgresql`) |
| Пул соединений | HikariCP (автоконфигурация Boot), `maximum-pool-size: 10` задан явно, `lock_timeout = 5s` через `connection-init-sql` |
| Брокер | Apache Kafka 4.1.1 (`apache/kafka:4.1.1`, KRaft, один узел) |
| Кэш | Redis 8 (`redis:8.10-alpine`) |
| Образы приложений | сборка `maven:3.9-eclipse-temurin-21` (**тег не подтверждён**, может потребоваться замена), запуск `eclipse-temurin:21-jre` |
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

- **account-service** — владелец балансов и проводок: атомарно выполняет списание и зачисление, проверяет баланс и (запланировано) владельца счёта. О Kafka не знает.
- **transfer-service** — оркестратор: публичное API переводов, идемпотентность клиента, статус перевода, вызов account-service с таймаутами и повтором, фоновое добивание зависших `PENDING`, outbox и публикация событий. Здесь же будут жить лимиты, холды, проверки (опциональные расширения).
- **notification-service** — потребитель событий, запись уведомлений.

Порядок одного перевода: проверить JWT → вставить `transfer` в статусе `PENDING` (при дубле ключа вернуть существующий) → вызвать account-service (вне транзакции БД) → в одной транзакции выставить `COMPLETED`/`FAILED` и записать событие в outbox. Сбой между вызовом и записью статуса закрывается повтором того же `transferId`: account-service идемпотентен.

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
6. **Transactional outbox:** событие пишется в `outbox_events` в той же транзакции, что и перевод; отдельный publisher (`@Scheduled`, `FOR UPDATE SKIP LOCKED`) отправляет в Kafka и ставит `published_at`. Доставка at-least-once, консьюмер идемпотентен.
7. **Защита от гонок:** основная — пессимистичная блокировка `SELECT ... FOR UPDATE`; `@Version` — страховка от забытой блокировки; перевод блокирует оба счёта одним запросом в порядке `id` (нет deadlock встречных переводов); `CHECK (balance >= 0)` — последний рубеж. Проверка повтора идемпотентной операции делается **после** блокировки счёта.
8. `account_transactions` и `audit_log` неизменяемы (триггеры запрещают UPDATE/DELETE); исправления — новыми записями.
9. **События:** один топик `transfers.events.v1`, тип события в заголовке Kafka `event-type` (`TransferCompleted`, `TransferFailed`). Правила контракта: только добавлять необязательные поля; несовместимое изменение — новый топик `v2`.
10. Двойная запись (ledger), холды, лимиты, конвертация валют — **опциональные расширения**, в базовую версию не входят.
11. **Временные сбои конкуренции** (таймаут блокировки, deadlock, конфликт версий) отдаются как **503 + `Retry-After: 1`**, коды `RESOURCE_BUSY` / `CONCURRENT_MODIFICATION`. Клиентское правило: 503 = повтори с тем же ключом. 409 остаётся только за конфликтом параметров (`TRANSFER_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`), он повтором не лечится.
12. **Защита `/internal/**` — общий секрет** в заголовке `X-Internal-Token` (переменная `INTERNAL_API_TOKEN`), отдельная цепочка безопасности; пользовательский JWT доступа к `/internal` не даёт. Путь развития — сервисный JWT.

### Формат ошибок account-service

Все ошибки: `{ timestamp, status, code, message, violations[] }` (`ApiError`; `violations` только при `VALIDATION_FAILED`). Стандартные ошибки Spring MVC (битый JSON, неверный UUID, нет заголовка) остаются 4xx с кодом `BAD_REQUEST`. Непредвиденное — 500 `INTERNAL_ERROR` без деталей наружу.

### JWT (запланировано, ещё не реализовано)

- Выдача токенов в account-service (таблица `users`, BCrypt, `POST /auth/register`, `POST /auth/login`), подпись RS256, срок access-токена ~15 минут; refresh-токены опциональны.
- transfer-service проверяет токен по **публичному** ключу (`spring-boot-starter-oauth2-resource-server`); закрытый ключ только у account-service.
- Обязательная проверка владения: `account.owner_id == sub` из токена; тесты на 401/403/200.
- Позже возможно: JWKS endpoint `/.well-known/jwks.json` вместо копирования публичного ключа.
- Ключи: `scripts/gen-keys.sh` → `keys/` (в `.gitignore`). Строки монтирования ключей в `docker-compose.yml` пока закомментированы.
- **Что придётся изменить в account-service при внедрении JWT:**
  - `SecurityConfig`: убрать `permitAll` для `/accounts/**`, подключить `oauth2ResourceServer`; ответы 401/403 привести к формату `ApiError`;
  - `CreateAccountRequest`: убрать `ownerId`, владелец берётся из `sub`;
  - проверка владельца во всех операциях со счётом (решить: 403 или 404 на чужой счёт; кто вправе пополнять);
  - `actor_id` в аудите из токена; действия `REGISTER`, `LOGIN`;
  - `InternalTransferRequest` получает `initiatorId`, `TransferService` проверяет, что счёт списания принадлежит ему (403) — в той же транзакции, под блокировкой;
  - `InternalTransferResponse` дополнить `ownerId` обоих счетов (нужны notification-service);
  - все MockMvc-тесты перевести на аутентифицированные запросы (меняется в основном `IntegrationTestBase`); добавить тесты регистрации, логина, 401/403, истёкшего токена, чужого счёта.

## 4. Минимальный набор операций (план)

account-service: создать счёт; пополнить; получить баланс; история операций (пагинация); внутренний атомарный перевод. **Реализовано.**
transfer-service: `POST /transfers` (заголовок `Idempotency-Key`); `GET /transfers/{id}`.
notification-service: потребление `TransferCompleted/Failed`, запись уведомления.
Валидации и ошибки: недостаточно средств, счёт не найден, разные валюты, сумма ≤ 0; единый формат ошибок (`@RestControllerAdvice`).

### API account-service (реализовано, без аутентификации пользователя)

| Метод и путь | Назначение | Ответы |
|---|---|---|
| `POST /accounts` `{ownerId, currency}` | создать счёт | 201 + `Location`; 422 `OWNER_NOT_FOUND`, `CURRENCY_NOT_SUPPORTED`; 400 `VALIDATION_FAILED` |
| `GET /accounts/{id}` | счёт с балансом | 200; 404 `ACCOUNT_NOT_FOUND` |
| `GET /accounts/{id}/balance` | баланс | 200; 404 |
| `POST /accounts/{id}/deposits` `{amount}` + заголовок `Idempotency-Key` (обязателен, ≤100 символов) | пополнить | 201 (выполнено), 200 (повтор того же ключа и суммы); 409 `IDEMPOTENCY_KEY_REUSED`; 400 `INVALID_IDEMPOTENCY_KEY`, `VALIDATION_FAILED`; 404 |
| `GET /accounts/{id}/transactions?page&size` | история, новые сверху | 200 `PageResponse` (`size` 1..100, по умолчанию 20; `sort` клиента игнорируется); 404 |
| `POST /internal/transfers` `{transferId, fromAccountId, toAccountId, amount, currency}` + `X-Internal-Token` | атомарный перевод | 201 (выполнено), 200 (повтор того же `transferId`); 409 `TRANSFER_CONFLICT`; 422 `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`; 404; 401 `UNAUTHORIZED` |

Сумма: больше 0, до 15 цифр в целой части и до 4 в дробной (лишние знаки отклоняются, не округляются). Любая операция может вернуть 503 (см. решение 11).

## 5. Структура БД

Миграции V1, V2 применялись на PostgreSQL 16 с проверкой ограничений; V3 проверяется интеграционными тестами на `postgres:17`.

**account_db** (V1, V2, V3):
- `currencies(code PK, name, minor_units)` — сидированы RUB, USD, EUR.
- `users(id UUID PK, email, password_hash, roles TEXT[], created_at, updated_at)`; уникальный индекс по `lower(email)`.
- `accounts(id, owner_id → users, currency → currencies, balance NUMERIC(19,4) CHECK >= 0, version, created_at, updated_at)`; индекс по `owner_id`. Статусов у счёта нет. Ограничения «один счёт на валюту» нет.
- `account_transactions(id, account_id → accounts, type IN (DEPOSIT, TRANSFER_DEBIT, TRANSFER_CREDIT), amount CHECK > 0, balance_after CHECK >= 0, transfer_id NULL, idempotency_key VARCHAR(100) NULL, created_at)`; `UNIQUE (transfer_id, type)`; CHECK: для переводов `transfer_id` обязателен, для пополнений должен быть NULL; **V3:** частичный уникальный индекс `uq_acc_tx_account_idem (account_id, idempotency_key) WHERE idempotency_key IS NOT NULL`, CHECK `idempotency_key` только у `DEPOSIT` и не пустой (у пополнений до V3 ключа нет); индекс `(account_id, created_at DESC, id DESC)`.
- `audit_log(id identity, actor_id NULL, action, entity_type, entity_id, details JSONB, created_at)`; индексы по сущности и актору. Действия: `CREATE_ACCOUNT`, `DEPOSIT`, `TRANSFER` (`actor_id` пока всегда NULL).
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
├── transfer-service/               # io.moneyflow.transfer, config/SecurityConfig, db/migration V1
└── notification-service/           # io.moneyflow.notification, db/migration V1
```

Пакеты по фичам. **account-service** (`io.moneyflow.account.*`):
- `auth` — `User`, `UserRepository` (регистрация и логин ещё не реализованы);
- `account` — `Account`, `AccountService`, `AccountController`, `Currency`, DTO (`CreateAccountRequest`, `DepositRequest`, `AccountResponse`, `BalanceResponse`, `DepositResult`), исключения;
- `transaction` — `AccountTransaction` (`@Immutable`, создаётся фабриками), `TransactionType`, репозиторий, `TransactionService`, `TransactionController`, `TransactionResponse`;
- `transfer` — `TransferService`, `InternalTransferController`, DTO и исключения внутреннего перевода;
- `audit` — `AuditLog`, `AuditService` (`Propagation.MANDATORY`), `AuditActions`;
- `common` — `ApiError`, `ApiException`, `GlobalExceptionHandler`, `PageResponse`;
- `config` — `SecurityConfig` (публичное API, `@Order(2)`), `InternalSecurityConfig` (`/internal/**`, `@Order(1)`), `InternalApiTokenFilter`, `InternalApiProperties`.

Тесты account-service (`src/test`): `support/IntegrationTestBase` + `PostgresTestConfig` (один контейнер Postgres на прогон, бином); интеграционные — `AccountApiTest`, `DepositAndHistoryTest`, `DepositIdempotencyTest`, `InternalTransferTest`, `InternalApiSecurityTest`, `LedgerImmutabilityTest`, `LockTimeoutTest` (свой контекст с `lock_timeout = 500ms`, поднимает отдельный контейнер); быстрые — `DomainModelTest`, `GlobalExceptionHandlerTest`, `InternalApiTokenFilterTest`. Классы названы `*Test`, а не `*IT`: CI запускает `mvn verify` без Failsafe, `*IT` не выполнились бы. Проводки и аудит не очищаются (триггеры), тесты создают уникальные данные.

## 7. Конфигурация и запуск

- `application.yml` каждого сервиса (в `src/main/resources/`) читает значения из переменных окружения со значениями по умолчанию для локальной разработки (остаются по решению автора):
  `SERVER_PORT`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATA_REDIS_HOST/PORT`, `ACCOUNT_SERVICE_URL`; в account-service ещё **`INTERNAL_API_TOKEN`** (по умолчанию `internal_dev_token`; пустое значение останавливает старт). Тот же секрет понадобится transfer-service для вызовов. Для Docker добавить переменную в `docker-compose.yml` (`INTERNAL_API_TOKEN: ${INTERNAL_API_TOKEN:-internal_dev_token}`) и в `.env.example`.
- Пароли по умолчанию: `account_dev_password`, `transfer_dev_password`, `notification_dev_password`; админ Postgres `postgres_dev_password`. Переопределяются через `.env` (`ACCOUNT_DB_PASSWORD` и т. д.). Реальные секреты не коммитить.
- Хостовые порты инфраструктуры: **Postgres 5433** (`POSTGRES_HOST_PORT`, чтобы не конфликтовать с локальным PostgreSQL на 5432), Kafka для IDE `localhost:29092`, внутри сети Docker `kafka:9092`, Redis 6379.
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

## 8. Статус

Сделано:
- [x] Каркас: Maven-модули, три пустых Spring Boot сервиса на нужных стартерах, `events-contract`
- [x] Миграции Flyway во всех трёх сервисах, init-скрипт баз и ролей
- [x] Dockerfile, docker-compose (профили, healthcheck, лимиты), CI на GitHub Actions
- [x] В каждом сервисе тест «контекст поднимается и миграции применяются» на Testcontainers
- [x] Минимальный `SecurityConfig` в account/transfer: открыты только `/actuator/health/**` и `/actuator/prometheus`, остальное закрыто
- [x] account-service: сущности и репозитории, создание счёта, баланс, пополнение с идемпотентностью (V3), история с пагинацией, внутренний атомарный перевод с идемпотентностью по `transferId`, аудит, единый формат ошибок, 503 при сбоях блокировок, защита `/internal/**` общим секретом, пул и `lock_timeout` в `application.yml`
- [x] Тесты account-service написаны (в том числе гонки: 50 параллельных списаний, встречные переводы, одинаковый `transferId`/`Idempotency-Key`)

Требует подтверждения автором (ИИ не запускал сборку и тесты — в его окружении нет Docker и зависимостей Maven):
- [ ] `mvn -pl account-service -am clean verify` — зелёный; особенно маппинг `CHAR(3)`, `text[]`, `jsonb` при `ddl-auto: validate` и `LockTimeoutTest` (ждёт 503)
- [ ] `INTERNAL_API_TOKEN` добавлен в `docker-compose.yml` и `.env.example`
- [ ] Ручная проверка по чек-листу, сборка и запуск контейнера account-service
- [ ] Зелёный `mvn -B verify` в CI после пуша

Не сделано: JWT, transfer-service и notification-service (бизнес-логика), outbox/Kafka, метрики, нагрузочные тесты, Kubernetes.

## 9. Следующие шаги (по порядку)

1. ~~account-service без аутентификации~~ — реализовано (см. «Статус»).
2. JWT в account-service: регистрация/логин, RS256, проверка владельца, `initiatorId` во внутреннем запросе, 401/403 в формате `ApiError`, тесты (список изменений — в разделе «JWT»).
3. transfer-service: `POST /transfers` с идемпотентностью, клиент к `/internal/transfers` (заголовок `X-Internal-Token`, таймауты, повтор по 503 и сетевым ошибкам с тем же `transferId`, отображение 422/404/403 в `FAILED` с `failure_reason`), статусы, фоновое добивание `PENDING`.
4. Outbox + Kafka producer, notification-service с consumer и защитой от дублей.
5. Метрики, нагрузочный тест с цифрами в README (размер пула и `lock_timeout` — параметры для подбора), затем манифесты Kubernetes (kind) и по желанию Elasticsearch для поиска по истории.

### Открытые вопросы

- Кто вправе пополнять счёт после JWT (только владелец или роль админа); 403 или 404 на чужой счёт.
- Ограничивать ли точность суммы по `currencies.minor_units` (сейчас допускается до 4 знаков независимо от валюты).
- Нужен ли `GET /accounts` для списка счетов текущего пользователя.
- Куда вынести DTO внутреннего API для клиента в transfer-service (дублировать или общий модуль; `events-contract` не подходит — без логики и зависимостей).
- Нужен ли read-only `GET /internal/transfers/{id}` для добивания `PENDING` (повторный `POST` уже безопасен).
- Замена общего секрета на сервисный JWT, если понадобится.
