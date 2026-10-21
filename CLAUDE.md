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
| `user-gateway` | Публичная точка входа для пользователей портала. Аутентификация по JWT из куки, проксирование в сервисы, в том числе SSE-потоков | 8080 |
| `user-auth` | Аккаунты, способы входа (email+пароль, Google), выпуск JWT, JWKS | 8081 |
| `game` | Единый игровой сервис: комнаты, партии **всех** игр, реплика профилей игроков (CDC из user-auth). Правила конкретных игр подключаются модулями `games/*` | 8082 |
| `admin-gateway` | Публичная точка входа админ-панели. Аутентификация по JWT admin-auth из куки, проксирование в сервисы | 8083 |
| `admin-auth` | Аккаунты администраторов (логин и пароль, без регистрации), выпуск JWT, JWKS | 8084 |

Отдельный сервис на каждую игру **не заводим**. Новая игра — новый модуль в `games/` (см. §2).

**Админ-панель** (`admin-gateway` + `admin-auth`):
- админка — CSR SPA на `http://localhost:3001`, в проде — отдельный поддомен того же домена (same-site). Браузер ходит в admin-gateway напрямую: CORS с `allowCredentials` и точным origin админки (§5);
- соглашения те же, что у user-gateway (§3): REST/JSON, ошибки — ProblemDetail с `code`, Swagger на русском, собственные DTO в `admin-gateway-public-sync-api`, OpenAPI на `/v3/api-docs` (permitAll). Фронт генерирует по нему отдельный клиент Orval;
- пути `/api/auth/*` (`login`, `refresh`, `logout`) и 401 `UNAUTHENTICATED` совпадают с user-gateway: на этом держится общий fetch-mutator фронта;
- текущий админ — `GET /api/admins/me` (по нему фронт определяет, вошёл ли админ), список админов — `GET /api/admins`;
- ролей нет, все админы равны;
- admin-auth и admin-gateway — упрощённая копия user-auth и user-gateway (без email, Google, CDC и Redis): имена классов и раскладка те же.

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
  game-api                     модель игры (GameRules, SessionEngine) и модули-механики core/turns/timer
  nothing-game                 «игра в ничто» — тестовая игра для проверки движка
  <игра>                       правила конкретной игры (например, games/carcassonne)
docker/                        Dockerfile, init-скрипты, dev-ключи, ACL Redis, конфиги Debezium Server
```

### Модули игр (`games/`)

- Модуль игры — **чистый Kotlin**: без Spring, БД, сети и прочего IO. Зависит только от `games/game-api`.
- **Модель игры — журнал событий-патчей и чистые функции.** Событие — `SessionEvent(code, method, path, value)`:
  - **`code`** — говорящее, детерминированное название произошедшего (`player-passed`, `turn-changed`, `game-paused`…). Его задаёт модуль-механика или игра, разные версии игры могут отличаться набором кодов. Состояние по `code` никто не меняет: он для журнала, логов и повтора;
  - **`method` + `path` + `value`** — как меняется документ партии `{status, meta, state}`. `path` — сегменты: строка — ключ объекта, `{id}` — элемент массива по id (у элементов адресуемых массивов обязателен `id`, в том числе у замаскированных). Одно событие — одна операция; действие, которое меняет несколько мест, — несколько событий со своими seq и кодами:
    - `PUT` — заменить или создать значение; если последний сегмент `{id}` и элемента нет — добавить в конец (значение — объект с тем же `id`);
    - `PATCH` — неглубокое слияние объекта по пути, `null` записывается как значение, путь должен существовать;
    - `DELETE` — удалить поле или элемент по id, путь должен существовать;
    - `INFO` — состояние не меняется, `value` — данные для журнала;
    - неподходящий путь или тип — `SessionPatchException` (на сервере — баг, на фронте — переподключение).
  - Применяет события **один общий применитель** `SessionPatch` (над JSON-деревом документа; `SessionCodec` переводит модели в дерево, реализация на Jackson — в сервисе, game-api без зависимостей). Своих редьюсеров у игр и модулей нет. Фронт применяет те же патчи той же семантикой.
- `GameRules` — параметры и контекст (код, число игроков, настройки, режимы времени) плюс функции, которые **порождают** события через построители `SessionEvents.put/patch/delete/info`:
  - `start` — начальное состояние; `SessionEngine` превращает его в первое событие журнала `session-started` = `PUT [] {status, meta: {game: {code, rules}}, state}`. `rules` — настройки, закреплённые при старте (нужны для повтора); версия игры в `meta.game` появится позже;
  - `decide(state, playerId, move)` → `Rejected(code)` или `Accepted(events)`;
  - `mask(event, viewerKey)` — маскирует только `value` для проекции (`Full`, `Public`, `Player(id)`); по умолчанию событие публично. Замаскированное событие доставляется со своим seq (у чужого игрока, например, `[{id: "c7"}]` вместо карты или `moveOptions: []`). После конца партии маски нет. Снапшот зрителя — свёртка его маскированных событий, отдельного `view` нет; инвариант «свёртка маскированных событий = маска полной свёртки» обязателен к тесту для каждой игры;
  - `moveOptions(state, playerId)` — допустимые действия игрока; фронт правил не знает и рисует только их;
  - `onTimeout(state)` — события при истечении времени хода.
- **Модули-механики** (`games/game-api`: `core`, `turns`, `timer`) подключаются игрой и не дублируются между играми. У каждого модуля свой блок состояния, свои коды и маска:
  - `core` — `players: [{id, moveOptions, …}]`; код `move-options-changed` (`PUT ["state","players",{id},"moveOptions"]`). После каждого изменения платформа выдаёт его игрокам, у которых изменились варианты (в конце и при отмене партии — пустые). Чужим игрокам `moveOptions` маскируются в `[]`;
  - `turns` — `{currentPlayerId, round, rounds}`, код `turn-changed` (`PUT ["state","turns"]`);
  - `timer` — `{status: RUNNING|PAUSED|STOPPED, deadline, pausedAt}`, коды `timer-started`/`timer-stopped` (`PUT ["state","timer"]`), `game-paused`/`game-resumed` (`PATCH ["state","timer"]`). **Пауза — часть таймера**: в игре без таймера пауз нет (409 `SESSION_NOT_PAUSABLE`). В конце и при отмене партии таймер переходит в `STOPPED`. Атрибуты игрока (например, запас времени в шахматах) хранятся в `players`, таймер может их менять;
  - сессия (`SessionEngine`): `session-started` (`PUT []`), `session-finished`/`session-cancelled` (`PATCH [] {status}`);
  - «ничто»: `player-passed` — `PATCH ["state"] {moves}`, по таймауту — `INFO {playerId, timeout: true}` (пропуски по времени в `moves` не считаются).
  - Будущие механики (например, стадии игры) — новые модули со своими кодами.
- Случайность (порядок тайлов, кубики) берётся из seed партии. Тогда события воспроизводимы на любом инстансе и при переигрывании журнала.
- Сервис `game/game` подключает все модули `games/*` и отвечает за всё остальное: комнаты, хранение, очерёдность, таймеры, события.
- Новая игра — это новый модуль `games/<игра>`. Кроме него нужны:
  - зависимость в `game/game/build.gradle.kts`;
  - бин правил в `GamesConfig`;
  - DTO состояния (`<Игра>SessionStateDto`) как новый вариант `oneOf` `SessionStateDto` в `game-user-gateway-sync-api` (снапшот разбирается строго в DTO игры); новые коды событий контракт не меняют;
  - значение в enum `GameCode` модели game и обоих контрактов (game и gateway), а также константа в `GameCodes`; `GameRules.code` модуля совпадает с кодом;
  - строка в таблице `game` DML-миграцией `<дата>-INSERT-game` (см. §4 «Каталог игр»). Пока игра в разработке — статус `NOT_PUBLISHED`, модуль правил и DTO для неё ещё не обязательны;
  - DTO настроек и границ (`<Игра>SettingsDto`, `<Игра>SettingsLimitsDto`) как новый вариант `oneOf` в обоих контрактах;
  - ветки в `RoomSettingsResponseMapper` (game) и `RoomMapper` (gateway).

  Новый сервис и DDL-миграции для этого не нужны.
- Что `GameRules` задаёт помимо функций:
  - **настройки игры:** класс, границы и значения по умолчанию (их отдаёт `GET /api/games/{gameCode}`), проверку;
  - **поддерживаемые режимы времени** и их границы. Сейчас есть `NONE` и `PER_MOVE`; расширение (`PER_ROUND`, `CHESS`) — новым значением `TimeControlMode`.
- Игроки партии закреплены до её конца; на отсутствие игрока партия не реагирует (время идёт, таймауты как обычно, ход по REST принимается). Хук игры на отсутствие игрока (шахматы — поражение/отмена, квиз — ничего) — решить при первой такой игре.
- Состояние и ход — простые data-классы без вычисляемых свойств; в журнал события попадают как JSON (`{path, value}`). Несовместимое изменение формата состояния или событий журнала — увеличить `stateSchemaVersion`.
- Если игре нужен отдельный процесс (real-time с WebSocket, особая нагрузка), для неё заводится отдельный сервис, который переиспользует тот же модуль из `games/`. Это архитектурное решение, только по согласованию.
- `games/carcassonne` создаётся, когда начнём писать правила Каркассона (пустые модули не заводим).

### Модули сервиса: целевая схема, создаются по необходимости

| Модуль | Содержимое | Когда создаётся |
|---|---|---|
| `<svc>/<svc>` | само приложение | всегда |
| `<svc>/<svc>-public-sync-api` | `@HttpExchange`-интерфейсы ручек, открытых наружу (фронт, внешние клиенты). Собственные DTO — только там, где gateway не просто проксирует (см. §3) | когда у сервиса появляются внешние ручки (сейчас только gateway'и) |
| `<svc>/<svc>-<gateway>-sync-api` | HTTP-интерфейсы (`@HttpExchange`) + DTO для gateway: `<svc>-user-gateway-sync-api` для user-gateway, `<svc>-admin-gateway-sync-api` для admin-gateway | когда gateway начинает проксировать в сервис |
| `<svc>/<svc>-internal-sync-api` | gRPC: `.proto` + стабы (без авторизации, защищает mesh) | при первом gRPC-методе |
| `<svc>/<svc>-internal-async-api` | Protobuf-контракты Kafka-событий | при первом событии |

- **Пустые модули «на вырост» не заводим.** Модуль появляется вместе с первым содержимым и удаляется, если опустел.
- Модули явно перечисляются в `settings.gradle.kts`.
- Сейчас есть: `user-auth/{user-auth, user-auth-user-gateway-sync-api, user-auth-internal-async-api}`, `user-gateway/{user-gateway, user-gateway-public-sync-api}`, `game/{game, game-user-gateway-sync-api}`, `admin-auth/{admin-auth, admin-auth-admin-gateway-sync-api}`, `admin-gateway/{admin-gateway, admin-gateway-public-sync-api}`.
- Контроллеры реализуют интерфейсы своего `*-sync-api` модуля, маршруты объявляются только в интерфейсе. Если контроллеру нужны `HttpServletRequest`/`HttpServletResponse`, они внедряются в конструктор (request-scoped прокси), а не попадают в сигнатуру контракта.
  - **Исключение — SSE-ручки сервиса.** Клиент HTTP Interface стримит только при типе `InputStream`, а сервер отдаёт поток через `SseEmitter`, поэтому одним методом это не выразить. SSE-ручка объявляется отдельным интерфейсом (`RoomEventsApi`: `@GetExchange(PATH, accept = text/event-stream)`, возвращает `InputStream`, путь — константа `PATH`), по нему gateway строит клиент. Контроллер сервиса интерфейс не реализует: он объявляет `@GetMapping(Api.PATH, produces = text/event-stream)` с `SseEmitter` и аннотирует Swagger на себе. Совпадение пути проверяет тест.
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
- **Модель** (`model/<фича>/<Имя>Model`) — то, с чем работают сервисы, контроллеры, листенеры, джобы и паблишеры. Перечисления — enum модели с префиксом сущности (`GameCode`, `RoomStatus`, `UserAvatar`). Id не nullable: модель существует только для сохранённой строки.
- **Маппер** entity ↔ модель — ручной `@Component` `mapper/<фича>/<Имя>EntityMapper` (`toModel`/`toEntity`), без кодогенерации.
- **DTO** контрактов (`*-sync-api`) живут только в контроллерах и их мапперах `mapper/<фича>/<Имя>ResponseMapper` (модель → DTO, DTO-enum → enum модели). Сервисы принимают и возвращают модели и примитивы, а составные ответы — композитными моделями (`RoomDetailsModel`, `AuthResultModel`). Данные для такой модели собирает сервис (`RoomDetailsService`); маппер в репозитории не ходит.
- **Репозиторий — всегда два слоя:**
  - `<Имя>Repository` (`@Repository`-класс) — единственный, кого видят сервисы. Принимает и возвращает модели и параметры (вставка — `insert(…)` с полями, а не готовой entity), маппит через бин-маппер;
  - `<Имя><Хранилище>Repository` — работа с конкретным хранилищем на entity: `…PostgresqlRepository` (Spring Data `CrudRepository` или `JdbcClient`), позже `…RedisRepository`. Используется только своим фасадом.
- Доменные enum'ы в DTO контрактов (`*-sync-api`) — свои; контроллер (или его маппер) переводит их в enum модели по имени (`RoomRole.valueOf(request.role.name)`).

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
- Неизвестный или отсутствующий дискриминатор полиморфного DTO (`InvalidTypeIdException` Jackson) — 400 `VALIDATION_ERROR` с ошибкой по полю `errors[<путь>.<дискриминатор>]`, например `settings.gameSettings.gameCode`.
- Advice библиотеки имеют самый низкий приоритет. **Собственный advice сервиса** объявляет `@Order(Ordered.HIGHEST_PRECEDENCE)`, иначе его исключения может перехватить ловушка `Exception` (пример — `DownstreamExceptionHandler` в gateway).
- `@Transactional(noRollbackFor = [QgamesException::class])` — когда изменение (счётчик попыток, отзыв сессии) должно сохраниться, несмотря на ошибку.

## 3. Взаимодействие

- **Фронт ↔ gateway:** REST/JSON. Ошибки — `ProblemDetail` с машиночитаемым полем `code`. Контракт — `<gateway>-public-sync-api` (`user-gateway-public-sync-api`, `admin-gateway-public-sync-api`). Gateway валидирует публичные запросы сам и не вызывает сервисы на заведомо невалидных данных.
  - **Простое проксирование — без дублей DTO:** если gateway только пересылает запрос и ответ, публичный интерфейс использует DTO из `<svc>-user-gateway-sync-api` проксируемого сервиса (`user-gateway-public-sync-api` зависит от них через `api`), без собственных копий и маппинга. Так сделаны все ручки gateway: каталог игр, комнаты, SSE-потоки, auth и users. Иначе одноимённые классы дают коллизию схем в OpenAPI: swagger-core называет схему по простому имени класса.
  - Собственные DTO и маппинг (`mapper/<feature>/`) — только когда gateway меняет форму данных. Если gateway лишь добавляет побочный эффект, используются DTO сервиса: auth ставит куки из `AuthResponse.tokens` и отдаёт `auth.user`, а внутренние DTO сессии (`AuthResponse`, `TokenPair`, `RefreshTokenRequest`) в публичный контракт не попадают (проверяет `OpenApiContractScenariosTest`).
- **Документация API (Swagger):** у каждой ручки в `*-sync-api` интерфейсе обязательно есть:
  - `@Operation(summary, description)` на русском: что делает метод, зачем он нужен и какие у него особенности;
  - `@ApiResponses` со всеми реально возможными статусами (200, 400, 401, …). Описания на русском, у ошибок в описании указаны значения `code` (например, «Неверный email или пароль (INVALID_CREDENTIALS)»);
  - у интерфейса — `@Tag(name, description)` на русском.

  Тело ошибок (`ProblemDetail` с полем `code`) в аннотациях не указываем: его всем ответам 4xx/5xx подставляет `ProblemDetailResponsesCustomizer` из `libs/common-web`. Коды ошибок в `description` метода не перечисляем, они описываются в `@ApiResponse`. Ручки без интерфейса (например, JWKS) аннотируются прямо на контроллере. Зависимость `swagger-annotations-jakarta` подключается в `*-sync-api` модули, её версия должна совпадать с той, что тянет springdoc.
- **OpenAPI для генераторов клиентов** (фронт строит клиенты через Orval по `/v3/api-docs` каждого gateway):
  - `required` проставляется автоматически (`KotlinRequiredPropertiesConverter` в `libs/common-web`): обязательны параметры конструктора DTO, которые не nullable и без значения по умолчанию. Исключение — параметр типа `Any?` без значения по умолчанию (любой JSON, в том числе null, например `PutData.value`): он тоже `required`. `@Schema(requiredMode)` на полях не пишем; необязательное поле — nullable или со значением по умолчанию;
  - обязательные строки запросов дополнительно помечаются `@NotBlank` (серверная проверка, 400 `VALIDATION_ERROR`). `@Size(max)` перебивает `minLength` от `@NotBlank` до 0, поэтому вместе с ним — `@Size(min = 1, max = …)`;
  - успешные ответы — `application/json` (`springdoc.default-produces-media-type` по умолчанию в `libs/common-web`), ошибки — `application/problem+json`;
  - SSE-ручка: ответ 200 — `text/event-stream` со схемой-union событий в components (`RoomStreamEvent`: конверты `{type, data}`, `oneOf` + `discriminator` по `type`). Orval не строит Zod для `text/event-stream`, фронт берёт Zod-схемы событий из components. `ResponseBodyEmitter` исключён из схем (`SpringDocUtils` в `libs/common-web`);
  - ключи документа (пути, схемы, ответы) отсортированы по алфавиту: `springdoc.writer-with-order-by-keys=true` по умолчанию в `libs/common-web`. Иначе springdoc отдаёт их в нестабильном порядке, и снапшот API на фронте меняется без изменения контракта. Проверяет `OpenApiContractScenariosTest` в gateway;
  - в схемах OpenAPI 3.1 тип задаётся через `types(setOf(…))`, а не `type(…)`;
  - регулярки в `@Pattern` должны работать и в Java, и в JS (`RegExp` с флагом `u`): только Unicode-классы (`\p{Cc}`), без Java-специфичных (`\p{Cntrl}`, `\p{javaLetter}`);
  - **полиморфные DTO** (например, настройки конкретной игры) оформляются так:
    - sealed-класс с `@JsonTypeInfo(EXISTING_PROPERTY)` и `@JsonSubTypes`;
    - `@Schema(oneOf, discriminatorProperty, discriminatorMapping)`;
    - дискриминатор — параметр конструктора с `@field:Schema(type = "string", allowableValues = [...])`.

    Чистый `oneOf` + `discriminator` без цикла `allOf` от swagger-core даёт `DiscriminatedUnionCustomizer` из `libs/common-web`. Форму схемы проверяет `OpenApiContractScenariosTest` в gateway. `jackson-annotations` (версия из BOM) подключается в `*-sync-api`.

    Дискриминатор настроек игры (`gameSettings`, `gameSettingsLimits`) — поле `gameCode`, значения — коды игр (`nothing`), константы из `GameCodes`.
- **Код игры — единственный идентификатор игры.** Enum `GameCode` в контрактах сериализуется строчным кодом (`@get:JsonValue val code`), поэтому в JSON и в OpenAPI значения — `nothing`, `carcassonne`, а не имена констант. Отдельного «типа игры» нет.
- **Код игры — в пути.** Ручка, которой нужна игра, живёт под `/api/games/{gameCode}/...`, сначала `games`, потом `rooms`:
  - каталог игр — `GET /api/games`, игра с параметрами правил — `GET /api/games/{gameCode}`;
  - создание комнаты — `POST /api/games/{gameCode}/rooms`;
  - настройки — `PUT …/rooms/{roomId}/settings`;
  - ход — `POST …/rooms/{roomId}/moves`.

  `{gameCode}` — строка с `allowableValues`. Код игры в теле запроса не передаётся. Неизвестный код gateway отклоняет сам (404 `GAME_NOT_FOUND`). Если код не совпадает с игрой комнаты — 409 `GAME_TYPE_MISMATCH`. Ручкам без привязки к игре (комната, вход, роль, партия) код не нужен.
- **Каталог игр доступен гостям:** `GET /api/games` и `GET /api/games/{gameCode}` в user-gateway — permitAll (только GET и только сами игры, комнаты требуют входа). Сервис game отдаёт каталог без `X-User-Id`. Гость с протухшей access-кукой получает на этих ручках 401 (Bearer-фильтр проверяет токен и на permitAll) — принято осознанно, 401 описан в `@ApiResponses`.
- **Gateway → сервисы:** Spring HTTP Interfaces (`@ImportHttpServices`), **не Feign**. Интерфейсы лежат в `<svc>-<gateway>-sync-api`; сервис реализует их контроллером (`class AuthController : AuthApi`), gateway строит по ним клиент. Базовый URL: `spring.http.serviceclient.<группа>.base-url`.
- **X-User-Id:** проставляет только `UserIdPropagationInterceptor` gateway из проверенного JWT (в admin-gateway это id админа). В сигнатурах `*-<gateway>-sync-api` userId нет — сервис берёт его через `RequestUserContext.userId()`.
- **Сервис ↔ сервис (синхронно):** gRPC через стартеры Spring Boot 4.1 (`spring-boot-starter-grpc-server/client`). Контракты — в `<svc>-internal-sync-api`.
- **Асинхронно:** брокер **Redpanda** (протокол Kafka, в коде обычный Kafka-клиент), сообщения в Protobuf из `<svc>-internal-async-api`. Консьюмеры идемпотентны.
  - Топики создаёт `redpanda-init` в docker-compose, автосоздание топиков выключено, чтобы их настройки были явными.
  - CDC-топики компактируемые, ключ — id агрегата: по ключу хранится последний снапшот, новый потребитель собирает реплику с нуля.
- **Real-time в играх:** REST (ходы и действия) + **SSE** (события).
  - **Цепочка:** браузер → `user-gateway` → `game`. Gateway проверяет JWT из куки **только при подписке**, ставит `X-User-Id` и пересылает поток из `game` байтами как есть (`ResponseBodyEmitter` с flush, клиент HTTP Interface в отдельной группе `game-stream` без read-timeout, заголовки `Cache-Control: no-cache`, `X-Accel-Buffering: no`). Ошибки до старта потока — обычный ProblemDetail через `DownstreamExceptionHandler`. `game` — внутренний сервис: что отдавать каждому подключению, решает он.
  - **Рассылка между инстансами `game`** — Postgres `LISTEN/NOTIFY` (канал `room_events`). `RoomEventPublisher` делает `pg_notify` в транзакции изменения, а Postgres доставляет уведомление только после коммита, поэтому outbox не нужен. В уведомлении только `{roomId, type}`; данные читает инстанс-получатель, если у него есть подписчики комнаты. `RoomEventListener` держит отдельное соединение вне пула, при простое проверяет его (`isValid`), а при обрыве переподключается и закрывает все локальные потоки — клиенты переподключатся и получат свежий снапшот.
  - **Подписки комнаты и партии — разные сущности.** Поток комнаты — `GET /api/rooms/{roomId}/events`: первым сообщением `snapshot` (как `GET` комнаты), затем `users-changed` (`ownerId` + полный список участников), `settings-changed`, `session-changed` (данных нет, клиент перечитывает комнату), `room-deleted` (поток закрывается). У событий комнаты нет seq: они несут полное значение (замена), повтор безопасен. Гонку снапшота с событиями закрывает маркер: подписчик регистрируется до транзакции подключения, в ней под `lock(room)` читается снапшот и шлётся `pg_notify` с маркером подписчика; всё полученное до маркера отбрасывается. Ping (`: ping`) раз в 5 с.
  - **Поток партии** — `GET /api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events` (внутренний `SessionEventsApi`, та же схема прокси). Подписаться может участник комнаты (в том числе отсутствующий игрок партии); `sessionId` чужой комнаты — 404 `SESSION_NOT_FOUND`. Аренды и присутствия у него нет.
    - Первый кадр — `snapshot` без обёртки: `{seq, status, serverTime, meta, state}` (`state` — свёртка маскированных событий зрителя). Остальные — `event:` = метод в нижнем регистре (`put`/`patch`/`delete`/`info`), `data:` = `{seq, at, method, code, data}`, где `data` — `{path, value}` (PUT/PATCH), `{path}` (DELETE) или объект (INFO); seq монотонный у каждого события журнала; замаскированные события тоже доставляются, поэтому дыра в seq — реальная потеря.
    - Подписчик регистрируется до снапшота: события с `seq ≤ N` снапшота отбрасываются, пришедшие раньше снапшота копятся. Маркер не нужен.
    - `session-finished`/`session-cancelled` закрывают поток. Подписка на завершённую партию — снапшот `Full` и закрытие (так вошедший видит последнюю партию).
    - Рассылка — `pg_notify('session_events', {sessionId, fromSeq, toSeq})`; диспетчер читает события из журнала один раз на инстанс и маскирует один раз на проекцию. `RoomEventListener` слушает оба канала и при обрыве закрывает оба реестра.
    - OpenAPI: union `SessionStreamEvent` по `type` (`SessionSnapshotEvent` и `SessionPatchStreamEvent`), внутри — `SessionPatchEvent` по `method` (`SessionEventPut/Patch/Delete/Info`); `PathSegment` = `string | {id}`; `code` — строка без enum (известные коды — в описании). `value` не типизирован: строго типизировано только состояние у снапшота — `oneOf` схем игр, игра определяется по `meta.game.code`.
  - Общая инфраструктура потоков: `SseWriter` (очередь и поток записи на подписчика), `StreamIndex`, `SseStreamProxy` в gateway.
  - Redis в real-time не участвует.
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

### Каталог игр (сервис `game`)

- Игры объявляются в таблице `game` (`code`, `title`, `description`, `sort_order`, `publish_status`) DML-миграциями (`<дата>-INSERT-game`). `room.game_code` и `room_session.game_code` ссылаются на `game(code)`. Статус позже будет менять админка.
- Набор кодов в таблице совпадает с enum `GameCode`, модуль правил обязателен для всех игр, кроме `NOT_PUBLISHED`. Это проверяет `GameCatalogVerifier` при старте `game` (после миграций); при расхождении сервис не стартует.
- **Статусы публикации** (`publish_status`, CHECK в DDL). Наружу (публичный `GamePublishStatus`) выходят только `PUBLISHED`, `DISABLED`, `HIDDEN`, поэтому `rules` в `GameDetailsResponse` всегда есть:

  | Статус | В `GET /api/games` | `GET /api/games/{gameCode}` | Новые комнаты и партии | Существующие комнаты и партии |
  |---|---|---|---|---|
  | `PUBLISHED` | да | да | да | работают |
  | `HIDDEN` | нет | да (доступ по прямой ссылке) | да | работают |
  | `DISABLED` | да (фронт: «Временно недоступна») | да | 409 `GAME_UNAVAILABLE` | работают, идущие партии продолжаются |
  | `NOT_PUBLISHED` | нет | 404 `GAME_NOT_FOUND` | 404 `GAME_NOT_FOUND` | все ручки комнат — 404 `GAME_NOT_FOUND`; комнаты не удаляются. Игра в разработке, модуля правил может не быть |
  | `FORCE_DISABLED` | нет | 404 `GAME_NOT_FOUND` | 404 `GAME_NOT_FOUND` | все ручки комнат — 404 `GAME_NOT_FOUND`; `ForceDisabledGameCleanupJob` отменяет партии и удаляет комнаты |

- Доступность игры проверяет `GameService`: `checkPlayable` — при создании комнаты (до проверки настроек) и старте партии, `checkAccessible` — во всех остальных операциях с комнатой, по игре комнаты под её блокировкой.
- **`ForceDisabledGameCleanupJob`** (ShedLock, `qgames.game.catalog.force-disabled-cleanup-enabled`, интервал 1m): пачкой находит комнаты `FORCE_DISABLED`-игр, каждую — `room FOR UPDATE SKIP LOCKED` с перепроверкой статуса, отменяет идущую партию и удаляет комнату (участники и партии — каскадно). `MoveTimeoutJob` и `RoomOwnerGraceJob` комнаты `FORCE_DISABLED`-игр пропускают (фильтр в SQL), комнаты `NOT_PUBLISHED`-игр — нет.
- Решить при реализации управления каталогом игр в админке:
  - запрет перевода игры обратно в `NOT_PUBLISHED`;
  - запрет публикации (любой статус, кроме `NOT_PUBLISHED`) игры без модуля правил — сейчас такая игра наружу не выдаётся (404), но статус это не запрещает;
  - завершение партий и удаление комнат при смене статуса вызовом из админки и судьба периодического `ForceDisabledGameCleanupJob`.

### Игровые комнаты (сервис `game`)

- **Публичные комнаты без поиска:** вход по ссылке с id комнаты для любого авторизованного пользователя. Приватные комнаты и поиск — решить при реализации.
- Комната создаётся под игру (`room.game_code` → `game.code`); создатель становится владельцем и первым игроком, когда подключится к её потоку (см. «Создатель комнаты»).
- **Вход:**
  - игрок идущей партии возвращается на своё место (PLAYER);
  - иначе, если партия не идёт и есть место, — PLAYER;
  - в остальных случаях — SPECTATOR;
  - повторный вход ничего не меняет.
- **Присутствие = открытый SSE-поток комнаты.** Ручек входа и выхода нет. Открытие потока — вход, закрытие последнего потока пользователя в комнате — выход. Подключений у пользователя может быть несколько (вкладки, устройства), каждое — строка `room_connection` с арендой (`lease` 20 с). `RoomConnectionHeartbeatJob` раз в 5 с шлёт ping в локальные потоки и продлевает аренду их подключений (`UPDATE … RETURNING id`); поток, чьё подключение не продлилось (его удалил чужой cleanup), закрывается — клиент переподключится и войдёт заново. `RoomConnectionCleanupJob` удаляет протухшие подключения упавших инстансов и уводит пользователей без подключений. Обрыв клиента замечается только по неудачной записи ping, обычно за 10–30 с.
- **Выход** — закрытие последнего потока. Таймеров выхода нет: участник сразу удаляется из комнаты, повторный вход — обычный новый вход (роль по правилам входа, при занятых местах — наблюдатель).
- **Игроки идущей партии закреплены до её конца:** при уходе они остаются участниками с `room_member.left_at` (в ответах `leaving = true`), вход снимает признак, место в партии (`room_session_player`) сохраняется. Конец или отмена партии (ход, таймаут, отмена) удаляют отсутствующих и публикуют `users-changed`. Последствия отсутствия решает игра (§2), у «ничто» их нет. Наблюдатели во время партии уходят сразу, как вне партии.
- **Окно владения** (`qgames.game.room.owner-grace`, 15 с; `room.owner_grace_user_id` и `owner_grace_deadline`): ушёл владелец — у комнаты нет владельца (`ownerId = null`, команды владельца не выполняет никто, `NOT_ROOM_OWNER`). Владелец-игрок идущей партии при этом остаётся отсутствующим игроком.
  - прежний владелец, вошедший в срок, снова владелец (роль — по входу);
  - новичок в окне владельцем не становится, даже в пустой комнате;
  - по истечении окна `RoomOwnerGraceJob` (интервал 1 с) отдаёт владение присутствующему: самому раннему игроку, затем наблюдателю; присутствующих нет — владельцем станет первый вошедший;
  - вход сам закрывает истёкшее, но ещё не обработанное окно. До срабатывания джоба (~1 с) после истечения окна команды владельца отвечают `NOT_ROOM_OWNER` — это допустимо;
  - пауза окно не замораживает. Владелец всегда присутствует в комнате: отсутствующие игроки партии владельцами не становятся.
- **Создатель комнаты** участником не вставляется: комната создаётся пустой (`empty_since = now`, в ответе создания `members` пустой, `ownerId = null`) с окном владения за создателем. Подключился — входит игроком и становится владельцем; не подключился — пустую комнату удалит `EmptyRoomCleanupJob`.
- **Роли** меняются только между партиями: игрок ↔ наблюдатель, игроком — если есть место. Это касается и владельца, владение при этом сохраняется.
- **Настройки комнаты** (правила времени и настройки игры) задаются при создании. Владелец меняет их между партиями, только в WAITING. Партия хранит свой снапшот настроек.
- **Владелец:**
  - передача владения — через окно владения (выше);
  - только владелец стартует партию, ставит её на паузу и продолжает, отменяет её (комната возвращается в ожидание).
- **Партии.** В комнате идут несколько партий подряд; после конца или отмены комната снова в WAITING, а последняя партия видна в ответе.
- **Автоудаление:** комната без присутствующих (подключённых) участников дольше `qgames.game.room.empty-ttl` (30m) удаляется `EmptyRoomCleanupJob` (ShedLock). Отсутствующие игроки партии комнату не держат. Джоб пачкой выбирает id, каждую комнату берёт `FOR UPDATE SKIP LOCKED` с перепроверкой, отменяет идущую партию и удаляет комнату (участники, подключения и партии — каскадно).
- **Конкурентность:** каждая мутация комнаты, участников и партии идёт под `SELECT … FOR UPDATE` строки `room`. Джобы (`MoveTimeoutJob`, `RoomConnectionCleanupJob`, `RoomOwnerGraceJob`, `EmptyRoomCleanupJob`, `ForceDisabledGameCleanupJob`) берут блокировки в том же порядке: сначала `room` (`FOR UPDATE … SKIP LOCKED`), потом перепроверка под блокировкой.
- **Real-time:** изменения комнаты идут по SSE-потоку комнаты, состояние партии — только по потоку партии (§3). В ответе комнаты партия краткая: `session {id, status}`; `session-changed` в потоке комнаты — на старт, конец и отмену партии.
- Смена ника (`UserProfileListener`) публикует `users-changed` во все комнаты пользователя **без блокировки комнат**: если из-за гонки со снапшотом кто-то не увидит новый ник, это допустимо.

### Сессии комнаты — партии (сервис `game`)

- Партия называется **сессией комнаты** (`room_session`, в коде `RoomSession*`). Таблицы общие для всех игр, своих таблиц у игры нет:
  - `room_session`: код игры (`game_code` → `game.code`), статус, `state_schema_version`, `seed`, `last_seq`, снапшот настроек комнаты, `move_deadline` (проекция таймера для индекса `MoveTimeoutJob`: дедлайн идущего таймера, на паузе NULL);
  - `room_session_player`: места игроков партии;
  - `room_session_event`: **журнал событий** (`session_id`, `seq`, `method` с CHECK, `code`, `data jsonb` в форме кадра, `created_at` — время события для повтора). Состояние партии **не хранится**: это свёртка журнала.
- **Состояние партии** собирает `SessionStateService` через абстракцию `SessionStateCache` (сейчас Caffeine, позже может быть Redis): ключ `(sessionId, viewerKey) → (seq, state)`. Берётся закешированное состояние на seq K, дочитываются события `K+1…N`, результат кладётся обратно; промах — свёртка с нуля. Журнал только дописывается, поэтому инвалидация между инстансами не нужна. Новое полное состояние попадает в кеш только после коммита.
- **Любое изменение партии** (старт, ход, таймаут, пауза, продолжение, конец, отмена) идёт через `SessionJournalService` под `lock(room)`: полное состояние на `last_seq` → события → `INSERT` в журнал с seq от `last_seq + 1`, обновление `last_seq`, статуса, `move_deadline` → `pg_notify`. На одно изменение может прийтись несколько событий, каждое со своим seq.
- **Мутации партии** (ход, пауза, продолжение, отмена) отвечают **204**: результат приходит событиями потока. Старт возвращает комнату.
- **Ручка хода одна на все игры:** `POST /api/games/{gameCode}/rooms/{roomId}/moves`.
  - `gameCode` — строчный код игры (`GameCode.code`: `nothing`, `carcassonne`). Неизвестный код — 404 `GAME_NOT_FOUND` (gateway отклоняет сам), не совпадает с игрой комнаты — 409 `GAME_TYPE_MISMATCH`.
  - Тело — общий конверт `{type, data}` (тот же конверт, что варианты в `moveOptions`): `type` — вид действия (`PASS`, `PLACE_TILE`), `data` — его параметры в свободном формате. Сервис превращает `{type, data}` в класс хода игры (`GameRules.moveClass`); неподходящий формат — 400 `VALIDATION_ERROR`.
  - Своих ручек у игр нет. Схема `data` в OpenAPI пока не типизируется — решить при появлении второй игры (Каркассон).
- Ход проверяется по текущему полному состоянию под блокировкой комнаты, **без проверки версии**: если хода нет среди допустимых — 422 `MOVE_REJECTED` с машиночитаемой причиной в поле `reason` (например, `NOT_YOUR_TURN`).
- **Таймер хода** (режим `PER_MOVE`, модуль `timer`):
  - старт и каждый ход ставят дедлайн `now + moveSeconds` (`timer-started`), конец и отмена партии останавливают таймер (`timer-stopped`, `STOPPED`);
  - `MoveTimeoutJob` раз в секунду обрабатывает истёкшие дедлайны по проекции `move_deadline`. Выборка идёт без блокировки, затем на каждую партию — `room FOR UPDATE SKIP LOCKED` с перепроверкой, в отдельной транзакции;
  - таймаут идёт тем же путём, что ход: `onTimeout` игры → события, новый дедлайн или конец партии;
  - ход, пришедший после дедлайна, но раньше джоба, принимается (окно ~1 с).
- **Пауза** (действие модуля `timer`; в игре без таймера — 409 `SESSION_NOT_PAUSABLE`):
  - владелец ставит идущую партию на паузу (`POST /api/rooms/{id}/session/pause`) и снимает её (`/resume`); повтор — 204 без события;
  - на паузе ход отклоняется с 409 `SESSION_PAUSED`;
  - `MoveTimeoutJob` пропускает партию на паузе (в SQL признак паузы — «идёт, таймер есть, `move_deadline` IS NULL»);
  - отмена партии разрешена;
  - `resume` сдвигает только дедлайн хода на длительность паузы. Окно владения пауза не замораживает.
- По журналу событий партию можно восстановить и переиграть (`created_at` событий позволяет повтор в реальном или ускоренном времени), поэтому формат событий версионируется.

### Миграции (Liquibase)

```
src/main/resources/db/changelog/
  db.changelog-master.yaml                      явный список include в хронологическом порядке (DDL и DML вперемешку)
  DDL/2026-10-17-CREATE-user.yaml               changeSet: id, author, runInTransaction, sqlFile + rollback sqlFile
  DDL/SQL/2026-10-17-CREATE-user.sql
  DDL/SQL/2026-10-17-CREATE-user-rollback.sql
  DML/<имя>.yaml, DML/SQL/<имя>.sql, DML/SQL/<имя>-rollback.sql
```

- **Имя миграции:** `<yyyy-MM-dd>-<ДЕЙСТВИЕ>-<таблица>`, например `2026-10-17-CREATE-user_identity`, `2026-11-02-ALTER-user`, `2026-11-02-INSERT-game`. Действие капсом: `CREATE`, `ALTER`, `DROP` (DDL), `INSERT`, `UPDATE`, `DELETE` (DML). Имя одновременно служит `id` changeSet'а. Если в один день над одной таблицей нужно два одинаковых действия — добавить уточнение в конец (`2026-11-02-ALTER-user-add_nickname`).
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
- **Пока проект нигде не используется** (нет прода и общих стендов с данными), ALTER-миграции не заводим: правится исходная CREATE-миграция таблицы, даже закоммиченная, а локальная БД пересоздаётся (`docker compose down -v`).
- С появлением прода или общего стенда закоммиченные миграции не редактируются, изменения идут только новыми миграциями.

### Redis

- Redis — **только кеш** сервиса (в real-time не участвует, см. §3): ключи только с префиксом `<svc>:*`, чужие ключи не читаем. В коде Redis пока не используется.
- **Сейчас (локально и на одном VPS)** — один контейнер `redis` без персистентности (`maxmemory` + `allkeys-lru`). Права разведены ACL (`docker/redis/users.acl`): каждый сервис — свой пользователь и только свой префикс ключей. В ACL ещё остались права на каналы `game:session:*` от отменённой шины — убрать при первом использовании Redis в коде.
- **При росте** кеш разносится по инстансу (или managed Redis) на сервис. Для этого достаточно правки конфигурации, код не меняется.
- Логические БД (`SELECT n`) не используем — их нет в Redis Cluster.

## 5. Аутентификация и безопасность

- `user-auth` подписывает access-JWT (**ES256**, TTL 15 мин, `sub` = userId) и публикует ключи на `/.well-known/jwks.json`. Gateway проверяет JWT локально (кеширует JWKS).
- **Refresh-токен:** случайная строка, в БД — только SHA-256; TTL 30 дней; ротация при каждом обновлении.
  - **Окно терпимости 10 с** (`qgames.auth.refresh-reuse-grace`). Повтор уже ротированного токена в течение окна, если в семье есть активный токен, выдаёт ещё одну пару той же сессии. Так обновляют сессию параллельные вкладки.
  - Повтор позже окна, после logout или после обнаружения кражи отзывает всё семейство (`family_id`) с ошибкой `REFRESH_TOKEN_REUSED`.
  - Ротацию от прочих отзывов отличает `refresh_token.rotated_at`.
- **Сессия админа (`admin-auth`):**
  - свой ключ подписи ES256 и issuer `qgames-admin-auth`, JWKS — на `/.well-known/jwks.json` admin-auth. Токен игрока admin-gateway не принимает, токен админа user-gateway — тоже;
  - access — 5 мин, refresh — 15 мин. Refresh скользящий: каждая ротация даёт полный TTL. Окно терпимости 10 с (`qgames.admin-auth.refresh-reuse-grace`), ротация и отзыв семьи — как у user-auth;
  - логин сравнивается точно, без lowercase и trim. При неизвестном логине проверяется dummy-хеш, чтобы время ответа не выдавало существующие логины.
- **Первый админ:** регистрации нет. При старте admin-auth `AdminBootstrapService` создаёт админа из `ADMIN_BOOTSTRAP_LOGIN` / `ADMIN_BOOTSTRAP_PASSWORD`, только если таблица `admin` пуста.
  - Вставка — одна команда `INSERT … WHERE NOT EXISTS (SELECT 1 FROM admin) ON CONFLICT (login) DO NOTHING`, поэтому безопасна при нескольких инстансах. Если админ создан, пишется лог WARN.
  - Значений по умолчанию в `application.yml` нет: без env бутстрап выключен, а если задано только одно из двух, сервис не стартует. docker-compose задаёт `admin` / `admin`.
- **Куки ставят и читают только gateway'и:** `HttpOnly; Secure; SameSite=Lax`; access — `Path=/`, refresh — `Path=/api/auth`. Сервисы за gateway о куках не знают — токены ходят в теле ответа.
  - Имена у каждого gateway свои: user-gateway — `access_token` / `refresh_token`, admin-gateway — `admin_access_token` / `admin_refresh_token` (`GatewayProperties.Cookies`). Куки не изолируются по порту, и локально одноимённые куки двух gateway перетёрли бы друг друга.
- CSRF-токены не используются (SameSite=Lax + только JSON). Сессий нет (stateless).
- **Домены:** фронт и API — поддомены одного домена (например, `qgames.site` и `api.qgames.site`). Для браузера это same-site, поэтому Lax-куки доходят до API и защита от CSRF без токенов остаётся валидной. Если фронт окажется на другом домене, это пересмотр модели CSRF, только по согласованию.
- **CORS** настраивается только в gateway'ях, для `/api/**`:
  - явный список origin из `CORS_ALLOWED_ORIGINS`, у каждого gateway — только свой фронт; wildcard запрещён и проверяется при старте:
    - user-gateway — портал (локально по умолчанию `http://localhost:3000`, в проде — домен портала). Админка (`http://localhost:3001`) сюда не добавляется: она ходит только в admin-gateway;
    - admin-gateway — админка (локально по умолчанию `http://localhost:3001`, в compose — из `ADMIN_CORS_ALLOWED_ORIGINS`), портал — нет;
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
- Ключи подписи — из конфигурации/секретов, никогда не генерируются при старте. `docker/dev-keys` — только для локальной разработки: `jwt-*.pem` у user-auth, `admin-jwt-*.pem` (kid `admin-dev-key-1`) у admin-auth.

## 6. Многоинстансность (у каждого сервиса может быть N инстансов)

- Никакого состояния между запросами в памяти инстанса. Исключения:
  - реестры открытых SSE-потоков (`RoomStreamRegistry`, `SessionStreamRegistry`): привязаны к соединениям этого инстанса, а присутствие, которое переживает инстанс, хранится в БД (`room_connection` с арендой);
  - кеш состояний партий `SessionStateCache` (Caffeine): только ускоряет сборку, источник правды — журнал, состояние на seq неизменно.
- Счётчики, лимиты, блокировки — в БД (атомарные `UPDATE … WHERE …`) или Redis.
- Гонки на уникальных ключах → 409 (`DuplicateKeyException` обрабатывается).
- Миграции — Liquibase (его lock защищает от параллельного запуска).
- Шедулеры:
  - джобы, которые часто обрабатывают строки по одной (`MoveTimeoutJob`, `RoomConnectionCleanupJob`, `RoomOwnerGraceJob`), работают без ShedLock: id выбираются без блокировки, затем `FOR UPDATE SKIP LOCKED` с перепроверкой, работу делят инстансы;
  - `EmptyRoomCleanupJob` и `ForceDisabledGameCleanupJob` — с ShedLock и тоже блокируют каждую комнату `FOR UPDATE SKIP LOCKED` с перепроверкой, транзакция на комнату;
  - у каждого джоба свой флаг включения `…-enabled`. В тестах джобы выключены и вызываются напрямую через `run()`.
- Kafka — consumer groups, идемпотентная обработка.
- Побочные эффекты (письма, события) — после коммита (`@TransactionalEventListener`) или через outbox. События SSE публикуются `pg_notify` в самой транзакции: Postgres доставляет их только после коммита.

## 7. Service mesh (кратко)

Внутренние API (`*-internal-sync-api`, REST-эндпоинты сервисов для gateway) не требуют авторизации и **доверяют** заголовку `X-User-Id`. Это безопасно только при закрытой сети:

- **Локально (compose):** mesh нет; сервисы в отдельной docker-сети, наружу HTTP-порты сервисов (8081, 8082, 8084) открыты только для Swagger при разработке.
- **В Kubernetes:** внедряем service mesh (Linkerd или Istio — выбрать при переходе на k8s). Рядом с каждым подом sidecar-прокси, весь трафик между сервисами шифруется mTLS автоматически, а политики (`AuthorizationPolicy` / `Server`+`AuthorizationPolicy` в Linkerd) разрешают: REST-порт сервиса — только для gateway'ев, которым он нужен, gRPC-порт — только сервисам внутри mesh. Ingress публикует наружу только gateway'и (`user-gateway`, `admin-gateway`). Код сервисов про mesh ничего не знает.

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

Kotlin 2.4, JDK 25+ (сборка на любом JDK ≥ 25 без toolchain, байткод — Java 25), Gradle 9.6 (configuration cache включён), Spring Boot 4.1 (Spring MVC + virtual threads, Jackson 3), springdoc 3.1, Spring Data JDBC, Liquibase, Testcontainers 2, springmockk 5. Версии — в `gradle/libs.versions.toml`.

## 10. Локальный запуск

```bash
./gradlew build                       # сборка + тесты (нужен Docker)
docker compose up --build -d --wait   # весь проект
docker compose down -v                # остановить и удалить данные
```

- Swagger gateway'ев: user-gateway — http://localhost:8080/swagger-ui.html, admin-gateway — http://localhost:8083/swagger-ui.html (сервисы: 8081, 8082, 8084).
- Вход в админку на стенде: `admin` / `admin`. БД `admin_auth` создаёт init-скрипт postgres, а он выполняется только на пустом томе: на старом стенде сначала `docker compose down -v`.
- Mailpit (письма с кодами): http://localhost:8025
- Google-вход: заполнить `GOOGLE_CLIENT_IDS` в `.env` (см. `.env.example`).
- Запуск сервиса из IDE: нужны `docker compose up -d postgres mailpit redpanda redpanda-init` (и `debezium-user-auth`, если нужен CDC); значения по умолчанию в `application.yml` смотрят на localhost, Redpanda снаружи — `localhost:19092`.
- Посмотреть CDC-события: `docker compose exec redpanda rpk topic consume outbox.event.user-auth.user-profile`.
- Образы: один общий multi-stage `docker/service.Dockerfile`, модуль задаётся аргументом `SERVICE`. Контекст сборки — корень репозитория.

## 11. Развёртывание (один VPS)

На старте всё работает на одном VPS через docker compose, без k8s и mesh. Экономим на инфраструктуре, а не на коде: сервисы остаются stateless и рассчитаны на несколько инстансов, а разнесение по машинам — это правка конфигурации.

- **Инфраструктура:**
  - один PostgreSQL (БД на сервис);
  - один Redis (кеш);
  - Redpanda (`--smp 1 --memory 512M`) и по Debezium Server (`-Xmx256m`) на каждую БД-источник CDC.
- **Образы** собираются в CI и пушатся в registry, на VPS только `docker compose pull`. Gradle-сборка на VPS съела бы 2–3 ГБ RAM рабочего сервера.
- **Наружу** открыт только reverse proxy с TLS, он проксирует в gateway'и. Внутренние порты, Swagger сервисов и Mailpit в проде не публикуются, письма уходят через внешний SMTP.
- **Память:** у каждого JVM-сервиса задан лимит памяти контейнера (`mem_limit`). Heap считается от него через `-XX:MaxRAMPercentage` (уже в `docker/service.Dockerfile`), без лимита JVM ориентируется на всю память хоста.
- **Бэкапы PostgreSQL:** `pg_dump` по расписанию, копии хранятся вне VPS.
- **Несколько инстансов:** `docker compose up --scale <svc>=N`, балансировка — reverse proxy через Docker DNS.
- **Оценка ресурсов.** Замер локального стенда в простое без admin-сервисов (3 JVM-сервиса) — ~1,9 ГБ: JVM-сервисы по 390–480 МБ, Debezium Server ~380 МБ, Redpanda ~105 МБ, PostgreSQL ~90 МБ, Redis ~5 МБ. admin-gateway и admin-auth добавляют вместе ~1 ГБ. Под нагрузкой heap растёт.

  | Конфигурация | RAM | VPS |
  |---|---|---|
  | user-gateway, user-auth, game + PostgreSQL + Redis + Redpanda + 1 Debezium | ~3–3,5 ГБ | 2 vCPU / 4 ГБ (впритык) |
  | + admin-gateway и admin-auth (текущий состав) и ещё источники CDC | ~5–6 ГБ | 4 vCPU / 8 ГБ |

  Каждая новая БД-источник CDC — это ещё один Debezium Server (~0,3–0,4 ГБ). Когда источников станет много, переход на Kafka Connect (один процесс на все коннекторы) — решить тогда.

- Reverse proxy (Caddy или nginx), CI (GitHub Actions или GitLab CI), registry и хранение секретов — решить при реализации деплоя. Для SSE (`text/event-stream`) у proxy должны быть выключены буферизация и короткий read-timeout (gateway уже отдаёт `X-Accel-Buffering: no`, ping раз в 5 с); при выборе proxy проверить.

## 12. Отклонённые решения

### Сервисы и модули

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Отдельный сервис на каждую игру | N баз, образов, деплоев и клиентов в gateway при одинаковой инфраструктуре пошаговых игр | Один сервис `game` + модули `games/*` |
| Отдельный сервис game-lobby | Комнаты и партии тесно связаны (старт комнаты создаёт партию); пришлось бы делать межсервисные вызовы и outbox | Комнаты внутри `game` |
| Логика игр в `libs/` | `libs/` — только инфраструктура без бизнес-логики | `games/` |
| Логика игр пакетами внутри сервиса | Границы между играми никто не контролирует, игру нельзя вынести в отдельный сервис | Модуль на игру в `games/` |
| Сразу 4 модуля на каждый сервис | Пустые модули «на вырост» засоряют сборку | Модули по необходимости |
| Собственные DTO gateway в `*-public-sync-api` + маппинг при простом проксировании | Дубли DTO и маппинга; одноимённые классы gateway и сервиса дают коллизию схем в OpenAPI | DTO сервисов через `api`-зависимость (§3); изменение DTO сервиса меняет публичный контракт — это видно по снапшоту OpenAPI на фронте |
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
| Браузер подключается к SSE игрового сервиса напрямую | Проверка JWT расползается по сервисам, внутренний сервис торчит наружу | SSE через прокси gateway (проверка JWT при подписке) |
| SSE терминируется в gateway, fan-out через Redis pub/sub | Gateway не знает, какому подключению что можно видеть (state у каждого игрока свой), нужна отдельная шина | Gateway проксирует поток в `game`, `game` решает по подключению; рассылка — Postgres LISTEN/NOTIFY |
| Redis pub/sub внутри `game` для рассылки между его инстансами | Нужны Redis-клиент, ACL, тест-контейнер и публикация после коммита | Postgres LISTEN/NOTIFY: уведомление доставляется после коммита само |
| Redis Streams для SSE fan-out | Сложнее (курсоры, очистка), а история не нужна: состояние приходит снапшотом | Postgres LISTEN/NOTIFY |
| Kafka для SSE fan-out | Долгие соединения и рассылку между инстансами не убирает, а задержка выше; каждый инстанс читает все события | Postgres LISTEN/NOTIFY |
| Короткий поллинг событий по `afterSeq` (в том числе каждым потоком из журнала) | Задержка доставки до интервала опроса, нагрузка на БД растёт с числом зрителей | Postgres LISTEN/NOTIFY |
| Long polling | Чтобы инстанс узнал о новом ходе, всё равно нужна рассылка между инстансами (или частый опрос БД): сложность как у SSE без его преимуществ | SSE |
| Sticky routing игроков партии на один инстанс | Сложная балансировка, при падении инстанса теряется партия | Postgres LISTEN/NOTIFY |
| Публичный SSE-контракт на собственных DTO gateway с разбором кадров | Лишний маппинг; одноимённые DTO gateway и сервиса дают коллизию схем в OpenAPI | Прокси байт как есть, DTO из sync-api сервиса |
| Общий интерфейс SSE-ручки, который сервис реализует через свой `InputStream` | Самописная замена `SseEmitter` (кадры, flush, обрыв клиента) | Отдельный интерфейс-клиент, контроллер с `SseEmitter` (§2) |
| seq/version у событий комнаты | Нет случая, где нужно версионировать состояние комнаты | События с полным значением + маркер против гонки со снапшотом |
| Отдельные события на вход, выход, роль, ник, владельца | Лишняя сложность на клиенте | Одно `users-changed` с владельцем и полным списком |
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
| Обработчик ошибок downstream-сервисов в `libs/common-exception` | Проброс ответа целевого сервиса — логика прокси, нужна только gateway | `DownstreamExceptionHandler` в каждом gateway |
| Отдельный контейнер postgres на сервис локально | Лишние ресурсы на локальном стенде | Один контейнер, БД на сервис |
| Сырой CDC таблиц через Debezium | Схема таблиц становится публичным контрактом | Outbox + Debezium |
| Outbox с поллингом из сервиса | Нагрузка на БД и больше кода | Debezium читает WAL |
| Отдельные таблицы на каждую игру | Каждая игра тащит свои миграции и репозитории в общий сервис | JSONB-снапшот + журнал ходов |
| `bigint identity` для id | Предсказуемые id, неизвестны до вставки | UUID v7 |
| UUID, генерируемый в приложении | Spring Data JDBC считает сущность с id существующей (нужен `Persistable`), а `RETURNING` закрывает потребность знать id | `DEFAULT uuidv7()` в БД |
| Имена таблиц во множественном числе | Решили единообразно в единственном | Единственное число, `"user"` в кавычках |
| Логические БД Redis (`SELECT n`) | Их нет в Redis Cluster | ACL-пользователь и префикс ключей на сервис |
| Отдельный Redis-контейнер на сервис (локально и на одном VPS) | Лишние ресурсы | Один `redis` с ACL и префиксом на сервис; при росте — разнос конфигурацией |
| Отдельные контейнеры redis-cache и redis-bus на одном VPS | Шина отменена (real-time — через LISTEN/NOTIFY), Redis только кеш | Один `redis` с ACL и префиксом на сервис |
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

### Каталог игр

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Флаг `disabled` у игры | Нужны разные режимы недоступности: скрыть из списка, временно запретить новые партии, отключить с удалением комнат | `publish_status` (см. §4 «Каталог игр») |
| Статус `ANNOUNCED` («скоро выйдет») в каталоге | Убран пользователем | Игра в разработке — `NOT_PUBLISHED`, наружу не выдаётся |
| `gameType` наряду с кодом игры (`GameType.NOTHING` + `nothing`) | Два идентификатора одной сущности, их приходится сопоставлять в каждом маппере и на фронте | Только `gameCode` (enum `GameCode` со строчными кодами), в том числе в дискриминаторе настроек |

### Игровые комнаты

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Игрок только в одной ожидающей комнате | Лишнее ограничение | Без ограничения |
| Владелец не может выйти / выход владельца удаляет комнату | Комната должна жить, пока в ней кто-то есть | Передача владения самому раннему (игроки, затем наблюдатели) |
| Пустую комнату наследует прежний владелец | Решили отдавать первому вошедшему | Первый вошедший в пустую комнату — владелец |
| Прерывать партию, если вышел её игрок | Игрок должен иметь возможность вернуться | Игрок закреплён до конца партии, последствия отсутствия решает игра |
| Автоудаление по неактивности | Решили считать пустоту, а не активность | Удаление, если нет ни игроков, ни наблюдателей дольше TTL |
| Пустой комнатой считать комнату без игроков | Наблюдатели тоже держат комнату | Пустая — без присутствующих (подключённых) участников, игроков или наблюдателей; отсутствующие игроки партии её не держат |
| «Игра в ничто» без ходов | Не проверяет очерёдность, журнал ходов и конец партии | Ходы PASS по очереди, число раундов из настроек (1–50) |
| Отложенный выход со сроками по ролям (владельцу 15 с, остальным 1 с, игроку партии `GameRules.reconnectGrace`) + `RoomLeaveJob` + заморозка сроков на паузе | Срок наблюдателя не переживал перезагрузку, сроки множились по ролям и усложняли паузу | Мгновенное удаление вне партии, закреплённые игроки партии (`left_at`), окно владения 15 с + `RoomOwnerGraceJob` |
| Таймер выхода у участников (15 с / 1 мин) | Пользователь выбрал модель без таймеров выхода | Мгновенное удаление + окно владения |
| Ручки `join`/`leave` (портал входит и выходит сам, выход — keepalive-запросом) | Присутствие держалось на запросах, которые теряются при закрытии вкладки; подписка на события и так нужна | Присутствие = открытый SSE-поток (`room_connection` с арендой) |
| Одно активное подключение на пользователя (новая вкладка вытесняет старую) | Решили поддержать несколько вкладок и устройств | Несколько подключений, выход по закрытию последнего |
| Отдельный срок `connect-grace` для создателя комнаты | Лишняя настройка | Создатель не вставляется участником, комната создаётся с окном владения за ним |
| Блокировать комнаты при смене ника | Пропущенная смена ника некритична | Без блокировок |
| Heartbeat SSE раз в 15 с | Обрыв замечался за 60–90 с (первая запись в закрытый сокет проходит, а звеньев два) | 5 с, аренда 20 с |
| Передавать владение самому раннему, даже отсутствующему | Владельцем на время становился отсутствующий | Только присутствующим; отсутствующие игроки партии владельцами не становятся |
| Отклонять ход, пришедший после дедлайна, до срабатывания джоба | Лишний код ошибки ради окна ~1 с | Ход принимается |
| ShedLock для частого построчного джоба (`RoomOwnerGraceJob`) | Ничего не добавляет к `FOR UPDATE SKIP LOCKED`, а при падении инстанса с захваченным локом джоб простаивает до `lockAtMostFor` | Без ShedLock, работу делят инстансы (§6) |
| Удаление комнаты владельцем | Хватает автоудаления пустых комнат (`EmptyRoomCleanupJob`), а ручное удаление выкидывает остальных участников и стирает историю партий | Только автоудаление пустых комнат |

### Настройки и таймеры

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| `gameType` в теле запроса (`POST /api/rooms {gameType}`) | Тип игры в каждой ручке передаётся по-разному | `gameCode` в пути `/api/games/{gameCode}/rooms/...` |
| Ключ настроек по имени игры (`nothing: {...}`) | Дублирует дискриминатор | Полиморфный `gameSettings` (`oneOf` по `gameCode`) |
| Плоские nullable-поля настроек / нетипизированный `settings` | Поля копятся с каждой игрой / нет типов на фронте | Полиморфный `gameSettings` + границы в `GET /api/games/{gameCode}` |
| Только `@Min/@Max` в DTO без ручки параметров игры | Границы одинаковы для всех игр, режимы фронт знает сам | `GET /api/games/{gameCode}` |
| Таймауты лениво при GET комнаты | GET меняет данные, нечего публиковать в SSE | `MoveTimeoutJob` раз в секунду |
| Дедлайны в Redis (sorted set) | Новая зависимость `game` от Redis, дедлайны теряются вместе с ним | `move_deadline` в `room_session` |
| Джоб блокирует сначала партию, потом комнату | Обратный порядок к ходу — дедлоки | Сначала `room FOR UPDATE SKIP LOCKED` |
| Пропуск хода по таймауту силами движка | Не во всех играх ход можно пропустить | `GameRules.onTimeout` |
| `GameRules.applyMove` → новое состояние, хранимый `room_session.state` + отдельный `view` для участника | Нет событий для потока и повтора; маскировка состояния и событий расходилась бы | Журнал событий, `decide` → события, общий применитель патчей для полного состояния и проекций, кеш состояний по seq |
| `version` партии + `expectedVersion` в ходе (409 `SESSION_CONFLICT`) | Событий много (личные, таймер), строгая сверка давала бы ложные конфликты | seq на каждое событие; ход проверяется по текущему состоянию, 422 `MOVE_REJECTED` |
| Ответы хода и действий с партией — вся комната с `session.state` | Гонка с потоком: старый ответ перетирал новые события | 204, состояние только из потока партии |
| Типизированные события (`started`, `player-changed`, `turns-changed`, `timer-changed`, `moved`) + `evolve` у каждой игры и модуля, редьюсер на каждый тип на фронте | Каждый новый код и версия игры требуют правок на обеих сторонах | Патчи `{method, path, value}` + говорящий `code`; один применитель на сервере и на фронте |
| Пауза на уровне сессии (`session-paused`/`session-resumed`, статус `PAUSED`) | В играх без таймера пауз нет | Пауза — статус таймера (`game-paused`/`game-resumed`) |
| JSON Pointer с индексами массивов (RFC 6902) в `path` | Индексы хрупкие: сдвиг массива — и маскированный клиент расходится с сервером | Сегменты с селектором `{id}` |
| Коллекции как объекты по id (`players: {u1: …}`) | Меняется форма состояния, порядок живёт отдельно | Массивы с `{id}` в пути |
| Список операций в одном событии (`ops: [...]`) | Грубее детализация кодов | Одно событие — одна операция |
| Глубокое слияние PATCH (JSON Merge Patch, RFC 7386, null удаляет) | Конфликтует с nullable-полями, дублирует DELETE | Неглубокое слияние, null = значение |
| Событие без метода для чисто информационных кодов | Неявный контракт | Метод `INFO` |
| Таймер `RUNNING` после конца партии | Финальное состояние не самодостаточно для повтора | `STOPPED` перед `session-finished`/`session-cancelled` |
| Начальное состояние функцией вне журнала | Для скрытых данных пришлось бы маскировать само состояние — тот же отдельный view | Первое событие `session-started` (`PUT []`), его тоже можно маскировать |
| `moveOptions` списком на верхнем уровне состояния | В полном состоянии варианты разных игроков смешались бы | `moveOptions` внутри игрока |

### Docker и тесты

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Jib | Нужен отдельный шаг `jibDockerBuild` перед compose | Multi-stage Dockerfile |
| bootBuildImage | Медленно и громоздко | Multi-stage Dockerfile |
| init.sql только с очисткой, без фикстур | Тесты создавали бы данные через API, медленнее и шумнее | init.sql с фикстурами + `TestData` |
| Программная очистка БД из кода теста | Состояние не видно из ресурсов | `init.sql` через `@Sql` |
