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
# 1. Инфраструктура (Postgres, Kafka, Redis). Значения по умолчанию подходят для локальной разработки;
#    чтобы переопределить пароли: cp .env.example .env
docker compose up -d

# 2. Сборка и тесты (Testcontainers поднимает свой PostgreSQL, нужен запущенный Docker)
mvn verify

# 3a. Сервисы из IDE: запустите *Application классы (порты 8081/8082/8083 заданы по умолчанию)
# 3b. ...или всё в контейнерах:
docker compose --profile apps up -d --build

# Проверка
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

Полный сброс (в том числе данных БД): `docker compose --profile apps down -v`.
Пароли БД применяются только при первом создании тома, после их смены нужен `down -v`.

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
- [ ] account-service: счета, пополнение, история, внутренний перевод
- [ ] JWT-аутентификация
- [ ] transfer-service: идемпотентные переводы, вызов account-service
- [ ] Outbox и публикация в Kafka, notification-service
- [ ] Нагрузочный тест, метрики, манифесты Kubernetes

Принятые решения: [docs/decisions.md](docs/decisions.md)
