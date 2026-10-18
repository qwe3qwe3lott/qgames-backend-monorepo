# syntax=docker/dockerfile:1.7
# Общий multi-stage образ для всех сервисов: модуль задаётся аргументом SERVICE (например, user-auth).
# Контекст сборки — корень репозитория, потому что сервисы зависят от libs/ и *-api модулей других сервисов.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# sharing=locked: compose собирает образы параллельно, а общий кеш Gradle нельзя использовать одновременно.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew ":${SERVICE}:${SERVICE}:bootJar" --no-daemon -x test

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home app
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/${SERVICE}/${SERVICE}/build/libs/app.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
