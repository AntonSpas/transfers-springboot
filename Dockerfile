# syntax=docker/dockerfile:1
# Один Dockerfile на все сервисы. Сборка: docker compose build  (MODULE передаётся из compose)
# Теги образов можно переопределить: --build-arg MAVEN_IMAGE=... --build-arg JRE_IMAGE=...
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre

# ---------- сборка ----------
FROM ${MAVEN_IMAGE} AS build
ARG MODULE
RUN test -n "${MODULE}" || (echo "Передайте --build-arg MODULE=<имя модуля>" && exit 1)
WORKDIR /workspace
COPY . .
# -am соберёт и events-contract; кэш ~/.m2 переиспользуется между сборками
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am package -DskipTests

# ---------- запуск ----------
FROM ${JRE_IMAGE}
ARG MODULE
# curl нужен для healthcheck в docker-compose
RUN command -v curl >/dev/null || (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)
RUN useradd --system --uid 1001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/*.jar app.jar
USER app
# Порт, на котором слушает приложение внутри контейнера (server.port читает SERVER_PORT)
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
