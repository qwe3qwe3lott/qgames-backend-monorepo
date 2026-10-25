# syntax=docker/dockerfile:1.7
# Multi-stage образ приложения (модуль app). Контекст сборки — корень репозитория: app зависит от модулей и libs/.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :app:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app.jar app.jar
USER app
# glibc по умолчанию заводит до 8 арен malloc на ядро: под нагрузкой это десятки МБ RSS вне учёта JVM.
ENV MALLOC_ARENA_MAX=2
# Память JVM под лимит контейнера (mem_limit 512m; замеры — CLAUDE.md §10):
# - SerialGC: один инстанс и heap в сотни МБ, у G1 лишние нативные структуры и потоки;
# - heap — половина лимита (256 МБ): остальное — metaspace (~75 МБ), code cache (~45 МБ), символы, стеки, malloc;
# - только C1 (TieredStopAtLevel=1): C2 под нагрузкой брал до 160 МБ нативных арен на компиляцию, и контейнер
#   убивался по OOM; C1 стоит больше CPU, но пиков памяти не даёт;
# - code cache и metaspace ограничены: их рост — понятная ошибка JVM, а не OOM-kill контейнера;
# - TrimNativeHeapInterval: glibc сам не отдаёт освобождённую malloc-память, JVM возвращает её ОС раз в 5 с;
# - при OutOfMemoryError процесс завершается, а не работает дальше в сломанном состоянии.
ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", \
    "-XX:MaxRAMPercentage=50", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:ReservedCodeCacheSize=64m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:TrimNativeHeapInterval=5000", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]
