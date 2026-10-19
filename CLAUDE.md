# CLAUDE.md — правила разработки qgames-backend

Бэкенд игрового портала qgames: микросервисы на Kotlin + Spring Boot в одном монорепозитории. Фронтенд — отдельный репозиторий `qgames-frontend-monorepo`, оркестратор агентов — `qgames-workspace`. Все три репозитория лежат рядом в одной родительской папке, поэтому фронтенд доступен по относительному пути `../qgames-frontend-monorepo`.

## 0. Процесс принятия решений (главное правило)

- **Архитектурные решения не принимаются без пользователя.** Новый сервис, технология, протокол, схема хранения, формат контракта, изменение границ сервисов, способ деплоя и т.п. — сначала вопрос пользователю с вариантами и рекомендацией, затем реализация.
- Если правило здесь противоречит задаче — спросить, а не обходить молча.
- **Не замалчивать проблемы.** Если по ходу работы что-то смущает (риск безопасности, противоречие правилам или ранее сказанному, сомнительное поведение, техдолг, расхождение кода и документации), сразу сказать пользователю и спросить, как поступить, предложив варианты. Не чинить молча и не откладывать «на потом» без согласования.
- **Git:** рабочая и основная ветка — `master` (`main` не используется). Коммиты делает пользователь; без явной просьбы не коммитить и не пушить.

### Как вести этот файл

- **Разделы 1–11 — принятые решения**, то есть действующие правила. Согласованное решение записывается в профильный раздел.
- **Раздел 12 — отклонённые решения.** Варианты, которые обсуждались и были отвергнуты, с причиной и тем, что выбрано вместо. Повторно такой вариант предлагать только с новым аргументом, и этот аргумент нужно назвать явно.
- **Журнала изменений и дат нет:** файл описывает текущее состояние. Если решение меняется, профильный раздел переписывается на месте, а прежнее решение переносится в раздел 12 с причиной отказа.
- Отложенные вопросы записываются в профильном разделе пометкой «решить при …», а не в отклонённых.

## 1. Сервисы

| Сервис | Назначение | Порт (HTTP) |
|---|---|---|
| `user-gateway` | Публичная точка входа для пользователей портала. Аутентификация по JWT из куки, проксирование в сервисы, SSE для игр (в будущем) | 8080 |
| `user-auth` | Аккаунты, способы входа (email+пароль, Google), выпуск JWT, JWKS | 8081 |
| `game` | Единый игровой сервис: комнаты, партии **всех** игр, реплика профилей игроков (CDC из user-auth). Правила конкретных игр подключаются модулями `games/*` | 8082 |

Отдельный сервис на каждую игру **не заводим**. Новая игра — новый модуль в `games/` (см. §2).

**Планируются** (для админ-панели):
- `admin-gateway` — точка входа админ-панели, аналог `user-gateway` для администраторов;
- `admin-auth` — аутентификация администраторов, аналог `user-auth`.

Их устройство — способы входа, роли и права, связь с `user-auth`, какие сервисы проксируются, порты — решить при реализации, по согласованию.

Требования фронта к admin-gateway (вход в это планирование, не реализовано):
- админка — CSR SPA на `http://localhost:3001`, в проде — отдельный поддомен того же домена (same-site). Браузер ходит в admin-gateway напрямую: CORS с `allowCredentials` и точным origin админки;
- те же соглашения, что у user-gateway (§3): REST/JSON, ошибки — ProblemDetail с `code`, Swagger на русском, собственные DTO в `admin-gateway-public-sync-api`, OpenAPI на `/v3/api-docs` (permitAll), `required`/nullable и `application/json` — общими механизмами `libs/common-web`. Фронт генерирует для него отдельный клиент Orval;
- сессия в HttpOnly-куках с отдельным refresh-эндпоинтом. **Имена кук свои** (например, `admin_access_token`/`admin_refresh_token`): куки не изолируются по порту, и локально (оба gateway на `localhost`) одноимённые куки перетрут друг друга. Настраивается через `GatewayProperties.Cookies`;
- аналог `GET /api/users/me` для текущего администратора — по нему фронт определяет, залогинен ли админ, а по ролям/правам — какие разделы показывать.

gRPC-порты появятся вместе с первым internal API (план: 90xx, отдельный от HTTP порт).

## 2. Структура репозитория и модулей

```
buildSrc/                      convention-плагины: qgames.kotlin-library, qgames.spring-service
gradle/libs.versions.toml      версии; всё, чем управляет Spring Boot BOM, без версий
libs/                          общие ИНФРАСТРУКТУРНЫЕ модули (без бизнес-логики)
  common-exception             иерархия ошибок (QgamesException → ClientException/ServerException → …) и их обработчики, подключаются автоматически
  common-web                   X-User-Id, RequestUserContext, OpenAPI-кастомизация (тянет common-exception через api)
  common-test                  Testcontainers (singleton: PostgreSQL, Mailpit, Redpanda), WireMock, Mailpit-клиент
  common-cdc-source            OutboxPublisher: запись событий в outbox_event в транзакции бизнес-изменения
  common-cdc-consumer          настройки потребителя CDC (consumer group, десериализаторы, обработка «ядовитых» сообщений)
games/                         ПРАВИЛА ИГР (бизнес-логика, но без инфраструктуры)
  game-api                     SPI правил игры (GameRules): начальное состояние, применение хода, вид для участника, конец партии
  nothing-game                 «игра в ничто» — тестовая игра для проверки движка
  <игра>                       правила конкретной игры (например, games/carcassonne)
docker/                        Dockerfile, init-скрипты, dev-ключи, ACL Redis, конфиги Debezium Server
```

### Модули игр (`games/`)

- Модуль игры — **чистый Kotlin**: без Spring, БД, сети и прочего IO. Зависит только от `games/game-api`.
- Правила — детерминированные чистые функции «состояние + ход → новое состояние или ошибка». Случайность (порядок тайлов, кубики) берётся из seed, который хранится в состоянии партии. Тогда ход воспроизводим на любом инстансе и при переигрывании журнала.
- Сервис `game/game` подключает все модули `games/*` и отвечает за всё остальное: комнаты, хранение, очерёдность, таймеры, события.
- Новая игра — это новый модуль `games/<игра>`: зависимость в `game/game/build.gradle.kts`, бин правил в `GamesConfig` и значение в enum `GameType` контрактов (game и gateway). Новый сервис, БД и миграции для этого не нужны.
- Состояние и ход — простые data-классы без вычисляемых свойств: сервис хранит их как JSON. Несовместимое изменение формата состояния — увеличить `stateSchemaVersion`.
- Если игре нужен отдельный процесс (real-time с WebSocket, особая нагрузка), для неё заводится отдельный сервис, который переиспользует тот же модуль из `games/`. Это архитектурное решение, только по согласованию.
- `games/carcassonne` создаётся, когда начнём писать правила Каркассона (пустые модули не заводим).

### Модули сервиса: целевая схема, создаются по необходимости

| Модуль | Содержимое | Когда создаётся |
|---|---|---|
| `<svc>/<svc>` | само приложение | всегда |
| `<svc>/<svc>-public-sync-api` | `@HttpExchange`-интерфейсы и **собственные DTO** ручек, открытых наружу (фронт, внешние клиенты) | когда у сервиса появляются внешние ручки (сейчас только user-gateway) |
| `<svc>/<svc>-user-gateway-sync-api` | HTTP-интерфейсы (`@HttpExchange`) + DTO для gateway. Для будущего admin-gateway по той же схеме — `<svc>-admin-gateway-sync-api` (общий шаблон `<svc>-<gateway>-sync-api`) | когда gateway начинает проксировать в сервис |
| `<svc>/<svc>-internal-sync-api` | gRPC: `.proto` + стабы (без авторизации, защищает mesh) | при первом gRPC-методе |
| `<svc>/<svc>-internal-async-api` | Protobuf-контракты Kafka-событий и Redis pub/sub-каналов | при первом событии/канале |

- **Пустые модули «на вырост» не заводим.** Модуль появляется вместе с первым содержимым и удаляется, если опустел.
- Модули явно перечисляются в `settings.gradle.kts`.
- Сейчас есть: `user-auth/{user-auth, user-auth-user-gateway-sync-api, user-auth-internal-async-api}`, `user-gateway/{user-gateway, user-gateway-public-sync-api}`, `game/{game, game-user-gateway-sync-api}`.
- Контроллеры реализуют интерфейсы своего `*-sync-api` модуля, маршруты объявляются только в интерфейсе. Если контроллеру нужны `HttpServletRequest`/`HttpServletResponse`, они внедряются в конструктор (request-scoped прокси), а не попадают в сигнатуру контракта.
- Proto-модули собираются convention-плагином `qgames.proto-api` (protobuf-gradle-plugin, Java + Kotlin DSL; версия protoc — как protobuf-java в Spring Boot BOM). gRPC-генерация добавится в него вместе с первым gRPC-методом.
- CDC вынесен в два модуля: `libs/common-cdc-source` (outbox у источника) и `libs/common-cdc-consumer` (приём у потребителя).
- В KDoc не писать `games/*`, `libs/*` и т.п.: в Kotlin `/*` внутри комментария открывает вложенный комментарий и ломает компиляцию.
- Сервис может зависеть только от `libs/*` и от `*-api` модулей других сервисов, **никогда** от `<other>/<other>`.

### Пакеты: type-sliced

Корень: `site.qgames.<svc без дефисов>` (`site.qgames.userauth`). Сначала тип, потом фича:

```
controller/auth/AuthController.kt      ✅
service/auth/LoginService.kt
repository/user/UserRepository.kt
entity/user/UserEntity.kt
model/user/UserModel.kt
mapper/user/UserEntityMapper.kt
config/…, exception/…, util/…
auth/controller/AuthController.kt      ❌ (domain-sliced — не используем)
```

### Слои: entity, модель, репозиторий

- **Entity** (`entity/<фича>/<Имя>Entity`) — строка таблицы, ровно как в БД: перечисления хранятся `String`, nullable `id`/`version` для Spring Data JDBC. Entity живёт только в `entity/`, `mapper/` и `repository/`.
- **Модель** (`model/<фича>/<Имя>Model`) — то, с чем работают сервисы, контроллеры, листенеры, джобы и паблишеры. Перечисления — enum модели с префиксом сущности (`RoomGameType`, `RoomStatus`, `UserAvatar`). Id не nullable: модель существует только для сохранённой строки.
- **Маппер** entity ↔ модель — ручной `@Component` `mapper/<фича>/<Имя>EntityMapper` (`toModel`/`toEntity`), без кодогенерации.
- **DTO** контрактов (`*-sync-api`) живут только в контроллерах и их мапперах `mapper/<фича>/<Имя>ResponseMapper` (модель → DTO, DTO-enum → enum модели). Сервисы принимают и возвращают модели и примитивы, а составные ответы — композитными моделями (`RoomDetailsModel`, `AuthResultModel`). Данные для такой модели собирает сервис (`RoomDetailsService`); маппер в репозитории не ходит.
- **Репозиторий — всегда два слоя:**
  - `<Имя>Repository` (`@Repository`-класс) — единственный, кого видят сервисы. Принимает и возвращает модели и параметры (вставка — `insert(…)` с полями, а не готовой entity), маппит через бин-маппер;
  - `<Имя><Хранилище>Repository` — работа с конкретным хранилищем на entity: `…PostgresqlRepository` (Spring Data `CrudRepository` или `JdbcClient`), позже `…RedisRepository`. Используется только своим фасадом.
- Доменные enum'ы в DTO контрактов (`*-sync-api`) — свои; контроллер (или его маппер) переводит их в enum модели по имени (`RoomGameType.valueOf(request.gameType.name)`).

### Ошибки (`libs/common-exception`)

- Сервисы **не указывают `HttpStatus`**: статус задаёт класс ошибки.
  ```
  QgamesException (abstract: status, code, message, properties, cause)
  ├─ ClientException            400
  │  ├─ UnauthorizedException    401
  │  ├─ ForbiddenException       403
  │  ├─ NotFoundException        404
  │  ├─ ConflictException        409
  │  ├─ UnprocessableException   422
  │  └─ TooManyRequestsException 429
  └─ ServerException             500 (логируется с ERROR)
     └─ ServiceUnavailableException 503
  ```
- Бизнес-ошибки собраны в каталогах-фабриках `exception/<фича>/<Фича>Errors` (`RoomErrors.roomNotFound()` → `NotFoundException("ROOM_NOT_FOUND", …)`). Коды — часть контракта с фронтом. Общие коды и имя поля `code` — в `ErrorCodes`.
- Обработчики подключаются автоконфигурацией при подключении библиотеки (сервисы подключают её явно, `common-web` — через `api`):
  - `QgamesExceptionHandler` — `QgamesException` по родительскому типу → ProblemDetail с `code` и `properties`; 400 `VALIDATION_ERROR` для `@Valid` и битого тела; ловушка `Exception` → лог ERROR и 500 `INTERNAL_ERROR` без деталей. Стандартные исключения Spring MVC (404 маршрута, 405) обрабатывает `ResponseEntityExceptionHandler`;
  - `PersistenceExceptionHandler` (если в classpath есть spring-dao) — `DuplicateKeyException` и `OptimisticLockingFailureException` → 409 `CONFLICT`.
- Advice библиотеки имеют самый низкий приоритет. **Собственный advice сервиса** объявляет `@Order(Ordered.HIGHEST_PRECEDENCE)`, иначе его исключения может перехватить ловушка `Exception` (пример — `DownstreamExceptionHandler` в gateway).
- `@Transactional(noRollbackFor = [QgamesException::class])` — когда изменение (счётчик попыток, отзыв сессии) должно сохраниться, несмотря на ошибку.

## 3. Взаимодействие

- **Фронт ↔ gateway:** REST/JSON. Ошибки — `ProblemDetail` с машиночитаемым полем `code`. Контракт — `user-gateway-public-sync-api` с собственными DTO; gateway маппит их в DTO сервисов и обратно (`mapper/<feature>/`), внутренние DTO наружу не протекают. Gateway валидирует публичные запросы сам и не вызывает сервисы на заведомо невалидных данных.
- **Документация API (Swagger):** у каждой ручки в `*-sync-api` интерфейсе обязательно есть:
  - `@Operation(summary, description)` на русском: что делает метод, зачем он нужен и какие у него особенности;
  - `@ApiResponses` со всеми реально возможными статусами (200, 400, 401, …). Описания на русском, у ошибок в описании указаны значения `code` (например, «Неверный email или пароль (INVALID_CREDENTIALS)»);
  - у интерфейса — `@Tag(name, description)` на русском.

  Тело ошибок (`ProblemDetail` с полем `code`) в аннотациях не указываем: его всем ответам 4xx/5xx подставляет `ProblemDetailResponsesCustomizer` из `libs/common-web`. Коды ошибок в `description` метода не перечисляем, они описываются в `@ApiResponse`. Ручки без интерфейса (например, JWKS) аннотируются прямо на контроллере. Зависимость `swagger-annotations-jakarta` подключается в `*-sync-api` модули, её версия должна совпадать с той, что тянет springdoc.
- **OpenAPI для генераторов клиентов** (фронт строит клиент через Orval по `/v3/api-docs` user-gateway):
  - `required` проставляется автоматически (`KotlinRequiredPropertiesConverter` в `libs/common-web`): обязательны параметры конструктора DTO, которые не nullable и без значения по умолчанию. `@Schema(requiredMode)` на полях не пишем; необязательное поле — nullable или со значением по умолчанию;
  - обязательные строки запросов дополнительно помечаются `@NotBlank` (серверная проверка, 400 `VALIDATION_ERROR`). `@Size(max)` перебивает `minLength` от `@NotBlank` до 0, поэтому вместе с ним — `@Size(min = 1, max = …)`;
  - успешные ответы — `application/json` (`springdoc.default-produces-media-type` по умолчанию в `libs/common-web`), ошибки — `application/problem+json`;
  - в схемах OpenAPI 3.1 тип задаётся через `types(setOf(…))`, а не `type(…)`;
  - регулярки в `@Pattern` должны работать и в Java, и в JS (`RegExp` с флагом `u`): только Unicode-классы (`\p{Cc}`), без Java-специфичных (`\p{Cntrl}`, `\p{javaLetter}`).
- **Gateway → сервисы:** Spring HTTP Interfaces (`@ImportHttpServices`), **не Feign**. Интерфейсы лежат в `<svc>-user-gateway-sync-api`; сервис реализует их контроллером (`class AuthController : AuthApi`), gateway строит по ним клиент. Базовый URL: `spring.http.serviceclient.<группа>.base-url`.
- **X-User-Id:** проставляет только `UserIdPropagationInterceptor` gateway из проверенного JWT. В сигнатурах `*-user-gateway-sync-api` userId нет — сервис берёт его через `RequestUserContext.userId()`.
- **Сервис ↔ сервис (синхронно):** gRPC через стартеры Spring Boot 4.1 (`spring-boot-starter-grpc-server/client`). Контракты — в `<svc>-internal-sync-api`.
- **Асинхронно:** брокер **Redpanda** (протокол Kafka, в коде обычный Kafka-клиент), сообщения в Protobuf из `<svc>-internal-async-api`. Консьюмеры идемпотентны.
  - Топики создаёт `redpanda-init` в docker-compose, автосоздание топиков выключено, чтобы их настройки были явными.
  - CDC-топики компактируемые, ключ — id агрегата: по ключу хранится последний снапшот, новый потребитель собирает реплику с нуля.
- **Real-time в играх:** REST (ходы) + **SSE** (события). SSE терминируется в gateway. Fan-out между инстансами gateway — через шину Redis pub/sub (подключение `bus`, см. §4 Redis; канал на партию `game:session:{id}`, публикует сервис `game`, события с монотонным `seq`). После реконнекта клиент догружает состояние через REST. Имена каналов и формат событий — контракт в `game/game-internal-async-api` (Protobuf). В Redis Cluster — sharded pub/sub (`SPUBLISH`/`SSUBSCRIBE`).
- **WebSocket** — только для будущих игр с real-time перемещением игроков (сейчас таких нет).

## 4. Данные

- **PostgreSQL 18, отдельная БД и пользователь на каждый сервис.** Чужие БД/таблицы не читаем никогда.
- Нужны данные другого сервиса → **Transactional Outbox + Debezium Server** (Outbox Event Router) → read model у потребителя. Payload — Protobuf из `*-internal-async-api`. Таблицы сервиса наружу не публикуются (сырой CDC таблиц запрещён). Подробности — в подразделе «CDC».
- **Доступ к данным без ORM:** Spring Data JDBC (`CrudRepository`) для простого CRUD, сложное — `@Query` с SQL или `JdbcClient`. JPA/Hibernate запрещены.
- **Именование:** таблицы в единственном числе, snake_case (`user_identity`, `refresh_token`). Зарезервированные слова — в двойных кавычках (`"user"`), в своих SQL тоже.
- **ID:** UUID v7, генерируется в БД: `id uuid PRIMARY KEY DEFAULT uuidv7()`. В сущностях `@Id val id: UUID? = null`. Исключение — реплики (read model): первичный ключ приходит из источника.
- **Время:** `timestamptz`, в коде `Instant` из бина `Clock`. В `JdbcClient` время передаётся как `Timestamp` (JDBC-драйвер PostgreSQL не принимает `Instant`).

### CDC

- **Источник** (сейчас user-auth):
  - таблица `outbox_event` (`aggregate_type`, `aggregate_id`, `event_type`, `payload bytea`) и публикация `qgames_outbox` создаются CREATE-миграцией сервиса;
  - событие пишет `OutboxPublisher` (`libs/common-cdc-source`) в той же транзакции, что и бизнес-изменение, и сразу удаляет строку: Debezium читает INSERT из WAL, а таблица не растёт. В тестах удаление выключено (`qgames.cdc.outbox.delete-after-insert: false`), чтобы проверить событие в таблице;
  - события — **снапшоты** реплицируемых колонок строки (а не дельты) вместе с её `version`;
  - у реплицируемой таблицы источника есть колонка `version bigint NOT NULL DEFAULT 0` (Spring Data JDBC `@Version`). Она растёт при **любом** изменении строки, в собственных `UPDATE` — вручную (`version = version + 1`), и заодно даёт оптимистичную блокировку (409 при конфликте). Сохранение без изменений не выполняется, чтобы не публиковать пустые события.
- **Debezium Server** — по одному процессу на каждую БД-источник, конфиг в `docker/debezium/<svc>.properties`:
  - Postgres через `pgoutput`, публикация создаётся миграцией (`autocreate` выключен), `snapshot.mode=no_data`;
  - роль владельца БД имеет `REPLICATION`, у PostgreSQL `wal_level=logical` и `max_slot_wal_keep_size`;
  - EventRouter пишет в топик `outbox.event.<aggregate_type>` (свой шаблон имени задать нельзя — Quarkus-конфиг сам пытается подставить `${routedByValue}`), ключ — `aggregate_id`, значение — сырые байты `payload` (`debezium.format.value=binary`);
  - версия образа — **3.6.x**: в 3.7.0 нет `EventRouter` и бинарного конвертера.
- **Потребитель** (сейчас game):
  - `@KafkaListener` на топик из `*-internal-async-api`, настройки по умолчанию — из `libs/common-cdc-consumer` (consumer group = имя сервиса, чтение с начала топика);
  - применение идемпотентно: `INSERT … ON CONFLICT DO UPDATE … WHERE <реплика>.version < EXCLUDED.version` — повторы и перестановки безопасны;
  - битое сообщение логируется и пропускается, временные ошибки повторяются.
- **Таблица-реплика:**
  - называется `"<сервис_источник>#<таблица_источник>"`: сервис через `_`, в SQL — всегда в двойных кавычках. Например, `"user_auth#user"`;
  - содержит **только колонки оригинала** с теми же именами. Подмножество допустимо (email в game не реплицируется), своих колонок нет;
  - `version` реплики — версия источника на момент последнего *реплицированного* изменения. Изменения нереплицируемых колонок (например, `email_verified`) тоже увеличивают версию источника, но события не порождают;
  - миграция называется по обычному правилу, `#` в имени файла и id changeSet допустим: `2026-10-18-CREATE-user_auth#user`.
- **Реплики сейчас:** `game."user_auth#user"` (id, nickname, avatar, version) из события `UserProfileChanged` user-auth.
- Начальное наполнение реплики, если потребитель подключается к уже существующим данным источника (бэкфилл), — решить при первой такой необходимости.

### Игровые комнаты (сервис `game`)

- **Публичные комнаты без поиска:** вход по ссылке с id комнаты для любого авторизованного пользователя. Приватные комнаты и поиск — решить при реализации.
- Комната создаётся под тип игры; создатель — владелец и первый игрок.
- **Вход:**
  - игрок идущей партии возвращается на своё место (PLAYER);
  - иначе, если партия не идёт и есть место, — PLAYER;
  - в остальных случаях — SPECTATOR;
  - повторный вход ничего не меняет.
- **Выход** возможен всегда. Место игрока в идущей партии сохраняется (`room_session_player`), партия не прерывается, вернувшийся игрок продолжает с того же места.
- **Роли** меняются только между партиями: игрок ↔ наблюдатель, игроком — если есть место. Это касается и владельца, владение при этом сохраняется.
- **Владелец:**
  - при выходе владельца владение переходит к самому раннему из присутствующих — сначала игроки, затем наблюдатели;
  - если комната опустела, владельца нет, и им становится первый вошедший;
  - только владелец стартует партию, отменяет её (комната возвращается в ожидание) и удаляет комнату.
- **Партии.** В комнате идут несколько партий подряд; после конца или отмены комната снова в WAITING, а последняя партия видна в ответе.
- **Автоудаление:** комната без игроков и наблюдателей дольше `qgames.game.room.empty-ttl` (30m) удаляется шедулером `EmptyRoomCleanupJob` с ShedLock.
- **Конкурентность:** каждая мутация комнаты, участников и партии идёт под `SELECT … FOR UPDATE` строки `room`; ход дополнительно защищён версией партии.
- **Real-time пока нет:** клиент перечитывает `GET` комнаты. SSE + шина — по §3, когда дойдём.

### Сессии комнаты — партии (сервис `game`)

- Партия называется **сессией комнаты** (`room_session`, в коде `RoomSession*`). Таблицы общие для всех игр, своих таблиц у игры нет:
  - `room_session`: тип игры, статус, `state jsonb` (снапшот, формат задаёт модуль игры), `state_schema_version`, `seed`, `version`;
  - `room_session_player`: места игроков партии;
  - `room_session_move`: журнал ходов (`session_id`, `seq` = версия партии после хода, игрок, `move jsonb` — конверт хода как его прислал клиент).
- **Ручка хода одна на все игры:** `POST /api/games/{gameCode}/rooms/{roomId}/moves`.
  - `gameCode` — строчный код игры (`GameType.code`: `nothing`, позже `carcassonne`). Неизвестный код — 404 `GAME_NOT_FOUND` (gateway отклоняет сам), не совпадает с игрой комнаты — 409 `GAME_TYPE_MISMATCH`.
  - Тело — общий конверт `{type, data, expectedVersion}`: `type` — вид действия (`PASS`, `PLACE_TILE`), `data` — его параметры в свободном формате. Сервис превращает `{type, data}` в класс хода игры (`GameRules.moveClass`); неподходящий формат — 400 `VALIDATION_ERROR`.
  - Своих ручек у игр нет. Схема `data` в OpenAPI пока не типизируется — решить при появлении второй игры (Каркассон).
- Ход применяется в одной транзакции: читаем снапшот, применяем правила из модуля игры, затем `UPDATE room_session … WHERE id = :id AND version = :expected` и `INSERT` в `room_session_move`. Если обновилось 0 строк, партию уже изменил другой запрос: 409 `SESSION_CONFLICT`, клиент перечитывает состояние. Клиент может передать `expectedVersion`, чтобы не ходить по устаревшему виду.
- Отказ правил игры — 422 `MOVE_REJECTED` с машиночитаемой причиной в поле `reason` (например, `NOT_YOUR_TURN`).
- По журналу ходов партию можно восстановить и переиграть, поэтому формат ходов и состояния версионируется.

### Миграции (Liquibase)

```
src/main/resources/db/changelog/
  db.changelog-master.yaml                      явный список include в хронологическом порядке (DDL и DML вперемешку)
  DDL/2026-10-17-CREATE-user.yaml               changeSet: id, author, runInTransaction, sqlFile + rollback sqlFile
  DDL/SQL/2026-10-17-CREATE-user.sql
  DDL/SQL/2026-10-17-CREATE-user-rollback.sql
  DML/<имя>.yaml, DML/SQL/<имя>.sql, DML/SQL/<имя>-rollback.sql
```

- **Имя миграции:** `<yyyy-MM-dd>-<ДЕЙСТВИЕ>-<таблица>`, например `2026-10-17-CREATE-user_identity`, `2026-11-02-ALTER-user`, `2026-11-02-INSERT-game_type`. Действие капсом: `CREATE`, `ALTER`, `DROP` (DDL), `INSERT`, `UPDATE`, `DELETE` (DML). Имя одновременно служит `id` changeSet'а. Если в один день над одной таблицей нужно два одинаковых действия — добавить уточнение в конец (`2026-11-02-ALTER-user-add_nickname`).
- **Одна таблица — одна миграция.** Индексы и ограничения таблицы входят в её CREATE-миграцию.
- **DDL** (структура) и **DML** (данные) лежат в разных папках. Порядок выполнения задаёт только master: файлы перечисляются явно, `includeAll` не используем.
- yaml-changelog шаблон:
  ```yaml
  databaseChangeLog:
    - changeSet:
        id: 2026-10-17-CREATE-user
        author: A Kozlov            # git config user.name автора миграции
        runInTransaction: true
        changes:
          - sqlFile:
              path: SQL/2026-10-17-CREATE-user.sql
              relativeToChangelogFile: true
        rollback:
          - sqlFile:
              path: SQL/2026-10-17-CREATE-user-rollback.sql
              relativeToChangelogFile: true
  ```
- **Rollback-SQL обязателен** для каждой миграции.
- Закоммиченные миграции не редактируются — изменения только новыми миграциями.
- Пока изменение не закоммичено и общих окружений нет, ALTER-миграцию не заводим: правится исходная CREATE-миграция таблицы, а локальная БД пересоздаётся (`docker compose down -v`).

### Redis

- В коде у сервиса **два независимых подключения со своими свойствами**:
  - **`cache`** — кеш сервиса, ключи только с префиксом `<svc>:*`, чужие ключи не читаем;
  - **`bus`** — шина pub/sub для real-time событий (см. Real-time), каналы `game:session:*`.
- **Сейчас (локально и на одном VPS)** оба подключения указывают на один контейнер `redis` без персистентности (`maxmemory` + `allkeys-lru` — вытесняются только ключи кеша, pub/sub не затрагивается). Права разведены ACL (`docker/redis/users.acl`):
  - каждый сервис — свой пользователь и только свой префикс ключей;
  - на каналы шины `game` может только публиковать, `user-gateway` — только подписываться.
- **При росте** `cache` и `bus` разносятся по разным инстансам, а кеш — по инстансу (или managed Redis) на сервис. Для этого достаточно правки конфигурации, код не меняется.
- Логические БД (`SELECT n`) не используем — их нет в Redis Cluster.

## 5. Аутентификация и безопасность

- `user-auth` подписывает access-JWT (**ES256**, TTL 15 мин, `sub` = userId) и публикует ключи на `/.well-known/jwks.json`. Gateway проверяет JWT локально (кеширует JWKS).
- **Refresh-токен:** случайная строка, в БД — только SHA-256; TTL 30 дней; ротация при каждом обновлении.
  - **Окно терпимости 10 с** (`qgames.auth.refresh-reuse-grace`). Повтор уже ротированного токена в течение окна, если в семье есть активный токен, выдаёт ещё одну пару той же сессии. Так обновляют сессию параллельные вкладки.
  - Повтор позже окна, после logout или после обнаружения кражи отзывает всё семейство (`family_id`) с ошибкой `REFRESH_TOKEN_REUSED`.
  - Ротацию от прочих отзывов отличает `refresh_token.rotated_at`.
- **Куки ставит и читает только user-gateway:** `HttpOnly; Secure; SameSite=Lax`; access — `Path=/`, refresh — `Path=/api/auth`. Сервисы за gateway о куках не знают — токены ходят в теле ответа.
- CSRF-токены не используются (SameSite=Lax + только JSON). Сессий нет (stateless).
- **Домены:** фронт и API — поддомены одного домена (например, `qgames.site` и `api.qgames.site`). Для браузера это same-site, поэтому Lax-куки доходят до API и защита от CSRF без токенов остаётся валидной. Если фронт окажется на другом домене, это пересмотр модели CSRF, только по согласованию.
- **CORS** настраивается только в user-gateway, для `/api/**`:
  - явный список origin из `CORS_ALLOWED_ORIGINS` — только портал (локально по умолчанию `http://localhost:3000`, в проде — домен портала); wildcard запрещён и проверяется при старте. Админка (`http://localhost:3001`) сюда не добавляется: она ходит только в admin-gateway;
  - `allowCredentials = true`;
  - запросы с чужого origin отклоняются с 403.
- **Пароли:** Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`), соль внутри хеша.
- **Email:** подтверждение 6-значным кодом (хеш в БД, TTL 15 мин, ≤ 5 попыток — атомарный счётчик в БД, повторная отправка не чаще раза в минуту). Письма сейчас шлёт сам user-auth после коммита; позже — отдельный notification-сервис через Kafka.
- **Существование email не скрываем** (принято осознанно ради понятных ошибок на фронте). Регистрация на занятый адрес отвечает 409, повторная отправка кода во время кулдауна — 429, а для неизвестного или подтверждённого адреса resend отвечает 200 без письма. От перебора адресов защитит rate limit на gateway — решить при реализации rate limiting.
- **Google:** фронт получает ID token через Google Identity Services и шлёт `POST /api/auth/google`; проверяем подпись по JWKS Google, `iss`, `aud` (`GOOGLE_CLIENT_IDS`).
- **Профиль игрока:**
  - ник (3–32 символа, без пробелов по краям, **не уникален** — игроки различаются по id) и аватар из enum `Avatar { CAT, DOG }`, набор аватаров определён на фронте;
  - при регистрации по email ник и аватар обязательны;
  - новому Google-аккаунту генерируется ник `player-xxxxxx` и аватар `CAT`;
  - меняются через `PATCH /api/users/me/profile` (только переданные поля);
  - каждое изменение публикуется событием `UserProfileChanged` (CDC).
- **Способы входа:** `user_identity(provider, subject)`, к одному аккаунту можно привязать несколько. Привязка явная (из залогиненной сессии, `/api/users/me/login-methods/*`) или автоматическая при входе через Google — **только** если email подтверждён и у нас, и в Google.
- Ключи подписи — из конфигурации/секретов, никогда не генерируются при старте. `docker/dev-keys` — только для локальной разработки.

## 6. Многоинстансность (у каждого сервиса может быть N инстансов)

- Никакого состояния между запросами в памяти инстанса.
- Счётчики, лимиты, блокировки — в БД (атомарные `UPDATE … WHERE …`) или Redis.
- Гонки на уникальных ключах → 409 (`DuplicateKeyException` обрабатывается).
- Миграции — Liquibase (его lock защищает от параллельного запуска).
- Шедулеры — только с ShedLock. Kafka — consumer groups, идемпотентная обработка.
- Побочные эффекты (письма, события) — после коммита (`@TransactionalEventListener`) или через outbox.

## 7. Service mesh (кратко)

Внутренние API (`*-internal-sync-api`, REST-эндпоинты сервисов для gateway) не требуют авторизации и **доверяют** заголовку `X-User-Id`. Это безопасно только при закрытой сети:

- **Локально (compose):** mesh нет; сервисы в отдельной docker-сети, наружу HTTP-порты 8081–8082 открыты только для Swagger при разработке.
- **В Kubernetes:** внедряем service mesh (Linkerd или Istio — выбрать при переходе на k8s). Рядом с каждым подом sidecar-прокси, весь трафик между сервисами шифруется mTLS автоматически, а политики (`AuthorizationPolicy` / `Server`+`AuthorizationPolicy` в Linkerd) разрешают: REST-порт сервиса — только для gateway'ев, которым он нужен, gRPC-порт — только сервисам внутри mesh. Ingress публикует наружу только gateway'и (`user-gateway`, позже `admin-gateway`). Код сервисов про mesh ничего не знает.

## 8. Тесты

- Тесты **максимально интеграционные**: запросы через MockMvc в настоящие контроллеры на полном контексте, реальная PostgreSQL (Testcontainers), реальная отправка почты в Mailpit, внешние HTTP-системы — WireMock. Тривиальных тестов не пишем.
- **CDC в тестах:** у источника проверяем записанное событие в `outbox_event`; у потребителя — публикуем сообщение в Redpanda (Testcontainers) так же, как Debezium (ключ — id, значение — байты protobuf), и ждём результат в БД. Debezium проверяется сквозным сценарием на docker-compose.
- В каждом сервисе есть `BaseIntegrationTest`, от него наследуются все тестовые классы. Бины подключаются в нём через `@Autowired` или `@MockkSpyBean` (springmockk). Внешние системы (Mailpit, WireMock) сбрасываются в `@BeforeEach`.
- **Тестовые данные:** у сервиса с БД есть `src/test/resources/init.sql`. Он выполняется перед каждым тестом (`@Sql("/init.sql")` на `BaseIntegrationTest`): `TRUNCATE … CASCADE` всех таблиц сервиса и вставка базовых фикстур с фиксированными UUID. Константы фикстур лежат в `support/TestData.kt` и должны совпадать с init.sql. Новая таблица добавляется в TRUNCATE в init.sql.
- Тест, которому нужен готовый пользователь или сущность, берёт фикстуру, а не создаёт её через API. Через API идут только сценарии, которые проверяют сам процесс создания (регистрация, подтверждение email).
- Предпочитаем **параметризованные сценарные тесты** (`@ParameterizedTest` + `@MethodSource`), состояние проверяем в БД.
- Контейнеры — singleton на прогон (`libs/common-test`), чтобы контекст Spring переиспользовался между классами.
- Для запуска тестов нужен Docker.

## 9. Стек и версии

Kotlin 2.4, JDK 25, Gradle 9.6 (configuration cache включён), Spring Boot 4.1 (Spring MVC + virtual threads, Jackson 3), springdoc 3.1, Spring Data JDBC, Liquibase, Testcontainers 2, springmockk 5. Версии — в `gradle/libs.versions.toml`.

## 10. Локальный запуск

```bash
./gradlew build                       # сборка + тесты (нужен Docker)
docker compose up --build -d --wait   # весь проект
docker compose down -v                # остановить и удалить данные
```

- Gateway / Swagger: http://localhost:8080/swagger-ui.html (сервисы: 8081–8082)
- Mailpit (письма с кодами): http://localhost:8025
- Google-вход: заполнить `GOOGLE_CLIENT_IDS` в `.env` (см. `.env.example`).
- Запуск сервиса из IDE: нужны `docker compose up -d postgres mailpit redpanda redpanda-init` (и `debezium-user-auth`, если нужен CDC); значения по умолчанию в `application.yml` смотрят на localhost, Redpanda снаружи — `localhost:19092`.
- Посмотреть CDC-события: `docker compose exec redpanda rpk topic consume outbox.event.user-auth.user-profile`.
- Образы: один общий multi-stage `docker/service.Dockerfile`, модуль задаётся аргументом `SERVICE`. Контекст сборки — корень репозитория.

## 11. Развёртывание (один VPS)

На старте всё работает на одном VPS через docker compose, без k8s и mesh. Экономим на инфраструктуре, а не на коде: сервисы остаются stateless и рассчитаны на несколько инстансов, а разнесение по машинам — это правка конфигурации.

- **Инфраструктура:**
  - один PostgreSQL (БД на сервис);
  - один Redis (подключения `cache` и `bus`);
  - Redpanda (`--smp 1 --memory 512M`) и по Debezium Server (`-Xmx256m`) на каждую БД-источник CDC.
- **Образы** собираются в CI и пушатся в registry, на VPS только `docker compose pull`. Gradle-сборка на VPS съела бы 2–3 ГБ RAM рабочего сервера.
- **Наружу** открыт только reverse proxy с TLS, он проксирует в gateway'и. Внутренние порты, Swagger сервисов и Mailpit в проде не публикуются, письма уходят через внешний SMTP.
- **Память:** у каждого JVM-сервиса задан лимит памяти контейнера (`mem_limit`). Heap считается от него через `-XX:MaxRAMPercentage` (уже в `docker/service.Dockerfile`), без лимита JVM ориентируется на всю память хоста.
- **Бэкапы PostgreSQL:** `pg_dump` по расписанию, копии хранятся вне VPS.
- **Несколько инстансов:** `docker compose up --scale <svc>=N`, балансировка — reverse proxy через Docker DNS.
- **Оценка ресурсов.** Замер локального стенда в простое — ~1,9 ГБ: JVM-сервисы по 390–480 МБ, Debezium Server ~380 МБ, Redpanda ~105 МБ, PostgreSQL ~90 МБ, Redis ~5 МБ. Под нагрузкой heap растёт.

  | Конфигурация | RAM | VPS |
  |---|---|---|
  | 3 сервиса + PostgreSQL + Redis + Redpanda + 1 Debezium | ~3–3,5 ГБ | 2 vCPU / 4 ГБ (впритык) |
  | + admin-сервисы и ещё источники CDC | ~5–6 ГБ | 4 vCPU / 8 ГБ |

  Каждая новая БД-источник CDC — это ещё один Debezium Server (~0,3–0,4 ГБ). Когда источников станет много, переход на Kafka Connect (один процесс на все коннекторы) — решить тогда.

- Reverse proxy (Caddy или nginx), CI (GitHub Actions или GitLab CI), registry и хранение секретов — решить при реализации деплоя.

## 12. Отклонённые решения

### Сервисы и модули

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Отдельный сервис на каждую игру | N баз, образов, деплоев и клиентов в gateway при одинаковой инфраструктуре пошаговых игр | Один сервис `game` + модули `games/*` |
| Отдельный сервис game-lobby | Комнаты и партии тесно связаны (старт комнаты создаёт партию); пришлось бы делать межсервисные вызовы и outbox | Комнаты внутри `game` |
| Логика игр в `libs/` | `libs/` — только инфраструктура без бизнес-логики | `games/` |
| Логика игр пакетами внутри сервиса | Границы между играми никто не контролирует, игру нельзя вынести в отдельный сервис | Модуль на игру в `games/` |
| Сразу 4 модуля на каждый сервис | Пустые модули «на вырост» засоряют сборку | Модули по необходимости |
| Публичное API gateway на DTO сервисов | Изменение DTO сервиса молча меняет контракт с фронтом | Свои DTO в `*-public-sync-api` + маппинг |
| Без общих `libs` | Дублирование инфраструктурного кода в каждом сервисе | `libs/common-*` |

### Протоколы и взаимодействие

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| `@Schema(requiredMode = REQUIRED)` на каждом поле DTO | Легко забыть на новом поле, много шума | `KotlinRequiredPropertiesConverter` по nullability Kotlin |
| Ход без кода игры в пути (`/api/rooms/{id}/session/moves`, тело `{move: {...}}`) | Из запроса не видно, чей это ход; некуда повесить схему хода игры | `/api/games/{gameCode}/rooms/{roomId}/moves` + конверт `{type, data}` |
| Общая константа шаблона ника для публичного и внутреннего контрактов | Публичный контракт не зависит от внутреннего, а общий модуль ради одной константы избыточен | Копия `NicknameRules` в каждом контракте |
| gRPC-Web между фронтом и gateway | Нужен Envoy и кодогенерация на фронте, нет Swagger, для веб-портала выгоды нет | REST |
| Feign | Spring Cloud OpenFeign в maintenance-режиме | Spring HTTP Interfaces |
| WebSocket для Каркассона | Для пошаговой игры избыточно | REST + SSE; WS — только для real-time игр |
| SSE/WS прямо в игровом сервисе | Проверка JWT и fan-out расползаются по сервисам | SSE в gateway |
| Redis Streams для SSE fan-out | Сложнее (курсоры, очистка), а история не нужна: состояние догружается через REST | Redis pub/sub |
| Kafka для SSE fan-out (вместо Redis pub/sub) | Долгие соединения и рассылку между инстансами не убирает, а задержка выше; каждый gateway читает все события всех партий | Redis pub/sub |
| Короткий поллинг событий партии по `afterSeq` | Задержка доставки до интервала опроса и постоянные пустые запросы; решили сохранить мгновенную доставку | SSE + Redis pub/sub |
| Long polling | Чтобы инстанс узнал о новом ходе, всё равно нужна рассылка между инстансами (или частый опрос БД): сложность как у SSE без его преимуществ | SSE + Redis pub/sub |
| Sticky routing игроков партии на один инстанс | Сложная балансировка, при падении инстанса теряется партия | Redis pub/sub |
| Kafka-контракты в JSON | Совместимость схемы никто не проверяет | Protobuf |
| Avro + Schema Registry | Лишний компонент инфраструктуры | Protobuf без registry |
| grpc-spring-boot-starter (net.devh) | Отстаёт от новых версий Spring | gRPC-стартеры Spring Boot 4.1 |

### Стек

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Spring Boot 3.5 | Предыдущая линейка, срок поддержки короче | Spring Boot 4.x |
| WebFlux + корутины | Сложнее в разработке и отладке, нужен R2DBC | Spring MVC + virtual threads |
| JPA/Hibernate | Требование: без ORM | Spring Data JDBC + SQL |

### Данные

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Одна БД со схемой на сервис | Слабая изоляция | БД на сервис |
| Entity в сервисном слое | Сервисы зависят от формата хранения (строки вместо enum, nullable id) | Модели + бин-маппер в репозитории |
| MapStruct для entity ↔ модель | kapt замедляет сборку, тонкости с Kotlin data-классами | Ручные `@Component`-мапперы |
| Двухслойный репозиторий только при нескольких хранилищах | Spring Data-интерфейс сам по себе возвращает только entity; разнобой между сервисами | Всегда фасад + `…PostgresqlRepository` |
| Сервисы возвращают DTO контрактов | Бизнес-логика зависит от формата API | Модели; DTO — в контроллере через `*ResponseMapper` |
| Ассемблер ответа, который сам ходит в репозитории | Чтение данных смешано с маппингом | Сервис собирает композитную модель, маппер только преобразует |
| `ApiException(HttpStatus, …)` из сервисов | Сервисный слой знает про HTTP | Иерархия классов ошибок в `libs/common-exception` |
| Плоская иерархия ошибок | Нельзя одной веткой отличить ошибки клиента от ошибок сервера | `ClientException` / `ServerException` |
| Класс на каждую бизнес-ошибку | Десятки мелких классов без пользы | Каталоги-фабрики `<Фича>Errors` |
| Обработчик ошибок downstream-сервисов в `libs/common-exception` | Проброс ответа целевого сервиса — логика прокси, нужна только gateway | `DownstreamExceptionHandler` в user-gateway |
| Отдельный контейнер postgres на сервис локально | Лишние ресурсы на локальном стенде | Один контейнер, БД на сервис |
| Сырой CDC таблиц через Debezium | Схема таблиц становится публичным контрактом | Outbox + Debezium |
| Outbox с поллингом из сервиса | Нагрузка на БД и больше кода | Debezium читает WAL |
| Отдельные таблицы на каждую игру | Каждая игра тащит свои миграции и репозитории в общий сервис | JSONB-снапшот + журнал ходов |
| `bigint identity` для id | Предсказуемые id, неизвестны до вставки | UUID v7 |
| UUID, генерируемый в приложении | Spring Data JDBC считает сущность с id существующей (нужен `Persistable`), а `RETURNING` закрывает потребность знать id | `DEFAULT uuidv7()` в БД |
| Имена таблиц во множественном числе | Решили единообразно в единственном | Единственное число, `"user"` в кавычках |
| Логические БД Redis (`SELECT n`) | Их нет в Redis Cluster | ACL-пользователь и префикс ключей на сервис |
| Отдельный Redis-контейнер на сервис (локально и на одном VPS) | Лишние ресурсы | Один `redis` с ACL и префиксом на сервис; при росте — разнос конфигурацией |
| Кеш и шина через одно подключение в коде | Разнести их по инстансам потом можно было бы только правкой кода | Два подключения (`cache`, `bus`), на одном VPS — один инстанс |
| Отдельные контейнеры redis-cache и redis-bus на одном VPS | Лишний контейнер и ресурсы при мизерной нагрузке; разделение достигается ACL и двумя подключениями | Один `redis`, разнос — конфигурацией при росте |
| Встраиваемая БД (SQLite/H2) вместо PostgreSQL | Теряется работа нескольких инстансов, пришлось бы переписать SQL и миграции (`uuidv7`, `jsonb`), а экономия ~200 МБ | Один PostgreSQL, БД на сервис |
| Держать Kafka запущенной до первого события | ~1 ГБ RAM впустую | Брокер добавляется вместе с первым событием |
| Kafka + Kafka Connect (Debezium) | Самая тяжёлая связка (~1,5–2 ГБ) для одного VPS | Redpanda + Debezium Server |
| Redpanda + Kafka Connect | Kafka Connect тяжелее Debezium Server, а коннектор пока один | Redpanda + Debezium Server |
| Debezium Server 3.7.0 | В образе нет `EventRouter` и бинарного конвертера | 3.6.x |
| Реплицировать в game только id пользователя | Нечего показать в комнате; email раскрывать другим игрокам нельзя | Реплика id + ник + аватар |
| Собственные имена таблиц-реплик (`user_profile`) | Не видно, из какого сервиса и таблицы данные | `"<сервис>#<таблица>"` |
| `changed_at`/`updated_at` в реплике для порядка событий | Колонки нет в оригинале; время зависит от часов разных инстансов источника | `version` в оригинале и реплике |
| Реплика без колонки порядка | Нет защиты от повторного проигрывания старого события | `version` |
| Сборка образов на VPS | Gradle съедает 2–3 ГБ RAM рабочего сервера | Сборка в CI + registry |

### Миграции

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Время или порядковый номер в имени миграции | Порядок и так задаёт master | `<дата>-<ДЕЙСТВИЕ>-<таблица>` |
| Папка на каждую миграцию | Выбрана другая раскладка | yaml в `DDL/`, SQL в `DDL/SQL/` |
| `includeAll` | DML, зависящий от DDL, может выполниться не вовремя | Явный список в master |
| Фиксированный автор `qgames` | В `DATABASECHANGELOG` не видно, кто автор | `git config user.name` |
| Несколько таблиц в одной миграции | Сложнее откатывать и ревьюить | Одна таблица — одна миграция |

### Аутентификация

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Opaque-сессии в Redis | Поход в Redis на каждый запрос | JWT + refresh |
| HMAC-подпись JWT | Общий секрет в нескольких сервисах, сложнее ротация | ES256 + JWKS |
| Google Authorization Code flow на бэкенде | Редиректы, state/PKCE в Redis, сложнее | ID token с фронта |
| Подтверждение email ссылкой | Нужна отдельная страница на фронте | 6-значный код |
| Строгая ротация refresh без окна терпимости | Параллельные вкладки, обновляющие сессию одной кукой, разлогинивают пользователя | Окно терпимости 10 с |
| Полное сокрытие существования email (всегда 200, письмо «вы уже зарегистрированы») | Фронт теряет понятные ошибки | Явные 409/429, защита от перебора — rate limit |
| Refresh-токены в Redis | При потере данных Redis разлогинятся все пользователи | PostgreSQL |
| BCrypt | Слабее против GPU-перебора, ограничение в 72 байта | Argon2id |
| Фронт и API на разных доменах | Пришлось бы ставить `SameSite=None` и вводить CSRF-токены, а сторонние куки блокируются браузерами | Поддомены одного домена |
| Один origin через reverse proxy (`/api` на домене фронта) | Решили держать API на отдельном поддомене | Поддомен API + CORS |
| CORS с wildcard origin | С `allowCredentials` запрещён спецификацией, и любой сайт мог бы делать запросы с куками пользователя | Явный список `CORS_ALLOWED_ORIGINS` |
| Origin админки (3001) в CORS user-gateway | Админка ходит только в admin-gateway; лишний origin расширяет круг сайтов с доступом к сессии игрока | Только origin портала |
| `http://localhost:5173` в CORS по умолчанию | Порт Vite из первой настройки, портал живёт на 3000 | `http://localhost:3000` |
| Куки ставит user-auth | Работа с куками размазывается по сервисам | Куки только в gateway |
| Отдельный notification-сервис сразу | Потребовал бы outbox и Debezium уже в первой итерации | Письма шлёт user-auth (позже вынесем) |
| Автопривязка Google без подтверждённого email с обеих сторон | Риск захвата чужого аккаунта | Автопривязка только при verified email у нас и в Google |
| Только явная привязка способов входа | Неудобно для пользователя с подтверждённым email | Явная + безопасная автопривязка |
| Уникальный ник | Не нужен: игроки различаются по id | Неуникальный ник |
| Google-аккаунту: ник из профиля Google (`given_name`) | Другие игроки видели бы настоящее имя | Сгенерированный `player-xxxxxx` |
| Google-аккаунту: запрос ника через ошибку `PROFILE_REQUIRED` и повтор входа | Лишний шаг при первом входе | Сгенерированный ник, смена в профиле |
| Отдельная ручка смены только ника | Аватар тоже меняется | Одна `PATCH /profile` на ник и аватар |

### Игровые комнаты

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Игрок только в одной ожидающей комнате | Лишнее ограничение | Без ограничения |
| Владелец не может выйти / выход владельца удаляет комнату | Комната должна жить, пока в ней кто-то есть | Передача владения самому раннему (игроки, затем наблюдатели) |
| Пустую комнату наследует прежний владелец | Решили отдавать первому вошедшему | Первый вошедший в пустую комнату — владелец |
| Прерывать партию, если вышел её игрок | Игрок должен иметь возможность вернуться | Место сохраняется, партию заканчивает только владелец |
| Автоудаление по неактивности | Решили считать пустоту, а не активность | Удаление, если нет ни игроков, ни наблюдателей дольше TTL |
| Пустой комнатой считать комнату без игроков | Наблюдатели тоже держат комнату | Нет ни игроков, ни наблюдателей |
| «Игра в ничто» без ходов | Не проверяет очерёдность, журнал ходов и конец партии | Ходы PASS по очереди, 3 круга |

### Docker и тесты

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Jib | Нужен отдельный шаг `jibDockerBuild` перед compose | Multi-stage Dockerfile |
| bootBuildImage | Медленно и громоздко | Multi-stage Dockerfile |
| init.sql только с очисткой, без фикстур | Тесты создавали бы данные через API, медленнее и шумнее | init.sql с фикстурами + `TestData` |
| Программная очистка БД из кода теста | Состояние не видно из ресурсов | `init.sql` через `@Sql` |
