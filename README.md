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

Инфраструктура: PostgreSQL 17 (один контейнер, три базы, у каждого сервиса своя роль и доступ только к своей базе), Apache Kafka 4.1 (KRaft), Redis 8.

## Требования

Java 21, Maven 3.9+, Docker (с Compose v2).

## Быстрый старт

```bash
# 0. Ключи JWT (нужны account-service; один раз). keys/ в .gitignore
./scripts/gen-keys.sh

# 1. Инфраструктура (Postgres, Kafka, Redis). Значения по умолчанию подходят для локальной разработки;
#    чтобы переопределить пароли: cp .env.example .env
docker compose up -d

# 2. Сборка и тесты (Testcontainers поднимает свой PostgreSQL, нужен запущенный Docker)
mvn verify

# 3a. Сервисы из IDE: запустите *Application классы (порты 8081/8082/8083 заданы по умолчанию).
#     account-service нужны пути к ключам: JWT_PRIVATE_KEY_PATH и JWT_PUBLIC_KEY_PATH (см. .env.example)
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
- проводки и аудит неизменяемы (триггеры БД).

Переменные окружения: `SERVER_PORT` (по умолчанию 8081), `INTERNAL_API_TOKEN`, `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` (без значений по умолчанию: без ключей сервис не стартует); параметры подключения к БД — в `application.yml`.

Ключи JWT: `scripts/gen-keys.sh` создаёт `keys/private.pem` и `keys/public.pem`. Для запуска из IDE или `mvn spring-boot:run` укажите абсолютные пути в `.env` (Windows: прямые слэши, `C:/Projects/...`) — `.env` подхватывается через `spring.config.import`.
В Docker Compose ключи монтируются в `/keys` только для чтения, пути в контейнере заданы в `docker-compose.yml`. На Linux файл `private.pem` с правами 600 может быть недоступен пользователю внутри контейнера: тогда дайте ему чтение (`chmod 644`, только для локальной разработки).
Закрытый ключ нужен только account-service; transfer-service будет получать лишь `public.pem`.

Интеграционные тесты идут на реальном Postgres через Testcontainers (нужен запущенный Docker). Классы называются `*Test`: в CI работает только Surefire.
Проверка одного модуля: `mvn -pl account-service -am clean verify`.

## events-contract

Модуль без зависимостей и логики: записи событий (`TransferCompletedEvent`, `TransferFailedEvent`),
имена топиков (`Topics`) и типы событий (`EventTypes`). Его подключают сервис-отправитель и сервис-получатель,
поэтому они всегда одинаково понимают формат сообщения.

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
- [ ] transfer-service: идемпотентные переводы, вызов account-service
- [ ] Outbox и публикация в Kafka, notification-service
- [ ] Нагрузочный тест, метрики, манифесты Kubernetes

Принятые решения: [docs/decisions.md](docs/decisions.md)
