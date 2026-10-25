# CLAUDE.md — правила разработки qgames-backend

Бэкенд игрового портала qgames: модульный монолит (Spring Modulith) на Kotlin + Spring Boot в одном монорепозитории. Фронтенд — отдельный репозиторий `qgames-frontend-monorepo`, оркестратор агентов — `qgames-workspace`. Все три репозитория лежат рядом в одной родительской папке, поэтому фронтенд доступен по относительному пути `../qgames-frontend-monorepo`.

## 0. Процесс принятия решений (главное правило)

- **Архитектурные решения не принимаются без пользователя.** Новый модуль приложения, технология, протокол, схема хранения, формат контракта, изменение границ модулей, способ деплоя и т.п. — сначала вопрос пользователю с вариантами и рекомендацией, затем реализация.
- Если правило здесь противоречит задаче — спросить, а не обходить молча.
- **Не замалчивать проблемы.** Если по ходу работы что-то смущает (риск безопасности, противоречие правилам или ранее сказанному, сомнительное поведение, техдолг, расхождение кода и документации), сразу сказать пользователю и спросить, как поступить, предложив варианты. Не чинить молча и не откладывать «на потом» без согласования.
- **Git:** рабочая и основная ветка — `master` (`main` не используется). Коммиты делает пользователь; без явной просьбы не коммитить и не пушить.

### Как вести этот файл

- **Разделы 1–10 — принятые решения**, то есть действующие правила. Согласованное решение записывается в профильный раздел.
- **Раздел 11 — отклонённые решения.** Варианты, которые обсуждались и были отвергнуты, с причиной и тем, что выбрано вместо. Повторно такой вариант предлагать только с новым аргументом, и этот аргумент нужно назвать явно.
- **Журнала изменений и дат нет:** файл описывает текущее состояние. Если решение меняется, профильный раздел переписывается на месте, а прежнее решение переносится в раздел 11 с причиной отказа.
- Отложенные вопросы записываются в профильном разделе пометкой «решить при …», а не в отклонённых.
- **От технологии, приложения или фичи отказались — сведения о ней удаляются целиком**, включая отклонённые решения о ней в разделе 11. В разделе 11 остаются только альтернативы действующим решениям.

## 1. Модули приложения

Бэкенд — **модульный монолит** (Spring Modulith): одно приложение `app` (один процесс, порт **8080**) из модулей с явными границами. Модули общаются только через публичный контракт друг друга (`<m>-api`) и события; модуль можно вынести обратно в отдельный сервис.

| Модуль | Назначение | Пути API |
|---|---|---|
| `user-auth` | Аккаунты игроков, способы входа (Google; пароль, добавленный в профиле), выпуск JWT игроков, куки сессии портала | `/api/auth/*`, `/api/users/me/*` |
| `game` | Комнаты и партии **всех** игр; ник и аватар игроков читает из таблицы `user` модуля user-auth (только чтение, через `user-auth-api`). Правила конкретных игр подключаются модулями `games/*` | `/api/games/*`, `/api/rooms/*` |
| `admin-auth` | Аккаунты администраторов (логин и пароль, без регистрации), выпуск JWT администраторов, куки сессии админки | `/api/admin/auth/*`, `/api/admin/admins/*` |
| `app` | Приложение: `@SpringBootApplication`, Security (две цепочки), CORS, группы OpenAPI, `application.yml`, сводный Liquibase-master, Docker-образ | — |

Отдельный модуль на каждую игру **не заводим**. Новая игра — новый модуль в `games/` (см. §2).

**Портал и админка — два фронта одного приложения:**
- портал — `/api/**` (кроме `/api/admin/**`), OpenAPI-группа `portal` (`/v3/api-docs/portal`); админка — `/api/admin/**`, группа `admin` (`/v3/api-docs/admin`). Swagger UI один, с выбором группы. Фронт генерирует по каждой группе свой клиент Orval;
- админка — CSR SPA на `http://localhost:3001`, в проде — отдельный поддомен того же домена (same-site). Ходит в приложение напрямую: CORS с `allowCredentials` и точным origin админки (§5);
- соглашения у обеих групп одинаковые (§3): REST/JSON, ошибки — ProblemDetail с `code`, Swagger на русском;
- пути аутентификации (`…/auth/login`, `…/auth/refresh`, `…/auth/logout`) и 401 `UNAUTHENTICATED` устроены одинаково, отличается только префикс (`/api/auth`, `/api/admin/auth`): на этом держится общий fetch-mutator фронта;
- текущий админ — `GET /api/admin/admins/me` (по нему фронт определяет, вошёл ли админ), список админов — `GET /api/admin/admins`;
- ролей нет, все админы равны;
- admin-auth — упрощённая копия user-auth (без email, Google и событий): имена классов и раскладка те же.

## 2. Структура репозитория и модулей

```
buildSrc/                      convention-плагины: qgames.kotlin-library, qgames.spring-module, qgames.spring-service
gradle/libs.versions.toml      версии; всё, чем управляет Spring Boot BOM, без версий (Spring Modulith и Exposed — своими BOM)
libs/                          общие ИНФРАСТРУКТУРНЫЕ модули (без бизнес-логики)
  common-exception             иерархия ошибок (QgamesException → ClientException/ServerException → …) и их обработчики, подключаются автоматически
  common-web                   RequestUserContext, OpenAPI-кастомизация (тянет common-exception через api)
  common-test                  Testcontainers (singleton PostgreSQL), WireMock
games/                         ПРАВИЛА ИГР (бизнес-логика, но без инфраструктуры)
  games-api                    модель игры (GameRules, SessionEngine) и модули-механики core/turns/timer/result
  nothing-game                 «игра в ничто» — тестовая игра для проверки движка
  checkers-game                шашки (вариант правил — настройка комнаты, сейчас доступны русские)
  <игра>                       правила конкретной игры (например, games/carcassonne)
user-auth/, admin-auth/, game/ МОДУЛИ ПРИЛОЖЕНИЯ: <m>/<m> (реализация) и <m>/<m>-api (публичный контракт)
app/                           приложение: собирает модули; Security, CORS, OpenAPI, конфигурация, миграции, все интеграционные тесты
docker/                        Dockerfile приложения, dev-ключи
```

### Модули игр (`games/`)

- Модуль игры — **чистый Kotlin**: без Spring, БД, сети и прочего IO. Зависит только от `games/games-api`.
- **Модель игры — журнал событий-патчей и чистые функции.** Событие — `SessionEvent(code, method, path, value)`:
  - **`code`** — говорящее, детерминированное название произошедшего (`player-passed`, `turn-changed`, `game-paused`…). Его задаёт модуль-механика или игра, разные версии игры могут отличаться набором кодов. Состояние по `code` никто не меняет: он для журнала, логов и повтора;
  - **`method` + `path` + `value`** — как меняется документ партии `{status, meta, state}`. `path` — сегменты: строка — ключ объекта, `{id}` — элемент массива по id (у элементов адресуемых массивов обязателен `id`, в том числе у замаскированных). Одно событие — одна операция; действие, которое меняет несколько мест, — несколько событий со своими seq и кодами:
    - `PUT` — заменить или создать значение; если последний сегмент `{id}` и элемента нет — добавить в конец (значение — объект с тем же `id`);
    - `PATCH` — неглубокое слияние объекта по пути, `null` записывается как значение, путь должен существовать;
    - `DELETE` — удалить поле или элемент по id, путь должен существовать;
    - `INFO` — состояние не меняется, `value` — данные для журнала;
    - неподходящий путь или тип — `SessionPatchException` (на сервере — баг, на фронте — переподключение).
  - Применяет события **один общий применитель** `SessionPatch` (над JSON-деревом документа; `SessionCodec` переводит модели в дерево, реализация на Jackson — в модуле game, games-api без зависимостей). Своих редьюсеров у игр и модулей нет. Фронт применяет те же патчи той же семантикой.
- `GameRules` — параметры и контекст (код, число игроков, настройки, режимы времени) плюс функции, которые **порождают** события через построители `SessionEvents.put/patch/delete/info`:
  - `start` — начальное состояние (`GameStart`: игровая часть, очерёдность, поля игры у игроков); `SessionEngine` превращает его в первое событие журнала `session-started` = `PUT [] {status, meta: {game: {code, rules}, moveRules}, state}`. `rules` — настройки, закреплённые при старте (нужны для повтора); версия игры в `meta.game` появится позже;
  - `moveRules(settings)` — правила хода для подсказок ввода на клиенте (`meta.moveRules`, например флаги шашек); по умолчанию `null`. Записываются в `session-started` и не меняются; клиент вариантов игры не знает, только эти флаги, ход окончательно проверяет сервер;
  - `decide(state, settings, playerId, move)` → `Rejected(code)` или `Accepted(events)`; `settings` — настройки игры партии, платформа читает их из `meta.game.rules.gameSettings`;
  - `mask(event, viewerKey)` — маскирует только `value` для проекции (`Full`, `Public`, `Player(id)`); по умолчанию событие публично. Замаскированное событие доставляется со своим seq (у чужого игрока, например, `[{id: "c7"}]` вместо карты или `moveOptions: []`). После конца партии маски нет. Снапшот зрителя — свёртка его маскированных событий, отдельного `view` нет; инвариант «свёртка маскированных событий = маска полной свёртки» обязателен к тесту для каждой игры;
  - `moveOptions(state, playerId)` — допустимые действия игрока; фронт правил не знает и рисует только их;
  - `onTimeout(state)` — события при истечении времени хода.
- **Модули-механики** (`games/games-api`: `core`, `turns`, `timer`, `result`) подключаются игрой и не дублируются между играми. У каждого модуля свой блок состояния, свои коды и маска:
  - `core` — `players: [{id, moveOptions, …}]`; код `move-options-changed` (`PUT ["state","players",{id},"moveOptions"]`). После каждого изменения платформа выдаёт его игрокам, у которых изменились варианты (в конце и при отмене партии — пустые). Чужим игрокам `moveOptions` маскируются в `[]`;
    - **поля игры у игрока** (например, `color` в шашках): `PlayerState.game`, класс — `GameRules.playerClass` (по умолчанию `null` — полей нет), начальные значения — `GameStart.players` (обязательны для каждого игрока). В документе лежат прямо в объекте игрока (`{id, moveOptions, color}`), а не вложенным объектом; совпадение с полями модуля (`id`, `moveOptions`) — ошибка. Документ игрока собирает и читает `SessionEngine`, маска `moveOptions` на поля игры не влияет;
  - `turns` — `{currentPlayerId, round, rounds}`, код `turn-changed` (`PUT ["state","turns"]`); первого игрока выбирает игра: `TurnsModule.initial(players, rounds, first)` («ничто» передаёт первое место);
  - `timer` — `{status: RUNNING|PAUSED|STOPPED, deadline, pausedAt}`, коды `timer-started`/`timer-stopped` (`PUT ["state","timer"]`), `game-paused`/`game-resumed` (`PATCH ["state","timer"]`). **Пауза — часть таймера**: в игре без таймера пауз нет (409 `SESSION_NOT_PAUSABLE`). В конце и при отмене партии таймер переходит в `STOPPED`. Атрибуты игрока (например, запас времени в шахматах) хранятся в `players`, таймер может их менять;
  - `result` — итог партии `{outcome: WIN|DRAW, winnerIds, reason}` (`ResultState`; `reason` — машиночитаемая причина, набор задаёт игра), после старта `null`. Код `result-set` (`PUT ["state","result"]`, построители `ResultModule.set/win/draw`) — только один раз и только в том же наборе событий, что `session-finished`: это проверяет `SessionEngine`. У отменённой партии итога нет. Событие публичное. Блок `result` есть в состоянии каждой игры (в DTO — nullable `result`);
  - сессия (`SessionEngine`): `session-started` (`PUT []`), `session-finished`/`session-cancelled` (`PATCH [] {status}`);
  - «ничто»: `player-passed` — `PATCH ["state"] {moves}`, по таймауту — `INFO {playerId, timeout: true}` (пропуски по времени в `moves` не считаются). Итог не ставит (`result` всегда `null`);
  - шашки: `piece-moved` (`PATCH ["state","pieces",{id}] {square}`), `piece-captured` (`DELETE` на каждую сбитую), `piece-crowned` (`PATCH {kind: KING}`), `last-move-changed` (`PUT ["state","lastMove"]`), `counters-changed` (`PATCH ["state"] {quietMoves}`), `player-resigned` и `move-timed-out` (`INFO {playerId}`). Ходы генерируются и проверяются только по флагам правил хода варианта (`CheckersMoveRules`, они же — `meta.moveRules`); `moveOptions` — только типы действий (`MOVE` у того, чей ход, `RESIGN` у обоих), пути не перечисляются. Окончания: `NO_PIECES`, `NO_MOVES`, `RESIGN` (вне очереди), `TIMEOUT` — победа; `DRAW_RULE` — 30 ходов подряд в сумме (по 15 у каждой стороны) только дамками без взятий. Цвета — случайно по seed.
  - Будущие механики (например, стадии игры) — новые модули со своими кодами.
- Случайность (порядок тайлов, кубики) берётся из seed партии. Тогда события воспроизводимы при переигрывании журнала.
- Модуль приложения `game/game` подключает все модули `games/*` и отвечает за всё остальное: комнаты, хранение, очерёдность, таймеры, события.
- Новая игра — это новый модуль `games/<игра>`. Кроме него нужны:
  - зависимость в `game/game/build.gradle.kts`;
  - бин правил в `GamesConfig`;
  - DTO состояния (`<Игра>SessionStateDto`, с `result`; если у игрока есть поля игры — свой DTO игрока) как новый вариант `oneOf` `SessionStateDto` в `game-api` и ветка в `SessionEventResponseMapper.stateClass` (снапшот разбирается строго в DTO игры); новые коды событий контракт не меняют, но дописываются в `SessionEventCodes.DESCRIPTION`;
  - если игра задаёт `moveRules` — DTO (`<Игра>MoveRulesDto`) как новый вариант `oneOf` `SessionMoveRulesDto` (`SessionMetaDto.moveRules`, без дискриминатора) и ветка в `SessionEventResponseMapper.moveRulesClass`;
  - если у хода есть параметры — DTO данных хода (`<Игра>MoveDataDto`) в `MoveDataSchemas.CLASSES` (см. §4 «Ручка хода»);
  - значение в enum `GameCode` модели game и контракта `game-api`, константа в `GameCodes` и код в `allowableValues` параметра `{gameCode}` (`GameApi`, `GameRoomApi`); `GameRules.code` модуля совпадает с кодом;
  - строка в таблице `game` DML-миграцией `<дата>-INSERT-game` (см. §4 «Каталог игр»), та же строка — в `init/game.sql` и `TestData` тестов. Пока игра в разработке — статус `NOT_PUBLISHED`, модуль правил и DTO для неё ещё не обязательны;
  - DTO настроек и границ (`<Игра>SettingsDto`, `<Игра>SettingsLimitsDto`) как новый вариант `oneOf` `GameSettingsDto`/`GameSettingsLimitsDto` (`@JsonSubTypes`, `discriminatorMapping`);
  - ветки в `RoomSettingsResponseMapper` (game);
  - новые варианты union и enum — в `PortalOpenApiContractScenariosTest` (app).

  Новый модуль приложения и DDL-миграции для этого не нужны.
- Что `GameRules` задаёт помимо функций:
  - **настройки игры:** класс, границы и значения по умолчанию (их отдаёт `GET /api/games/{gameCode}`), проверку;
  - **поля игры у игрока** (`playerClass`) и **правила хода** для клиента (`moveRules(settings)`), если нужны;
  - **поддерживаемые режимы времени** и их границы. Сейчас есть `NONE` и `PER_MOVE`; расширение (`PER_ROUND`, `CHESS`) — новым значением `TimeControlMode`.
- Игроки партии закреплены до её конца; на отсутствие игрока партия не реагирует (время идёт, таймауты как обычно, ход по REST принимается). Хук игры на отсутствие игрока (шахматы — поражение/отмена, квиз — ничего) — решить при первой такой игре.
- Состояние и ход — простые data-классы без вычисляемых свойств; в журнал события попадают как JSON (`{path, value}`). Несовместимое изменение формата состояния или событий журнала — увеличить `stateSchemaVersion`.
- Если игре нужен отдельный процесс (real-time с WebSocket, особая нагрузка), для неё заводится отдельное приложение (сервис), которое переиспользует тот же модуль из `games/`. Это архитектурное решение, только по согласованию.
- `games/carcassonne` создаётся, когда начнём писать правила Каркассона (пустые модули не заводим).

### Модули приложения: `<m>` и `<m>-api`

| Gradle-модуль | Содержимое |
|---|---|
| `<m>/<m>` | реализация модуля: контроллеры, сервисы, репозитории, джобы, слушатели, changelog'и миграций. Библиотека (convention `qgames.spring-module`) без `@SpringBootApplication` и `bootJar` |
| `<m>/<m>-api` | публичный контракт модуля: REST-интерфейсы контроллеров (`@HttpExchange` со Swagger-аннотациями), DTO, события, константы и интерфейсы, которые модуль даёт другим модулям и app |
| `app` | приложение (convention `qgames.spring-service`): `QgamesApplication` (пакет `site.qgames`), Security, CORS, OpenAPI-группы, `application.yml`, сводный Liquibase-master, `bootJar`, все интеграционные тесты |

- Сейчас есть: `user-auth/{user-auth, user-auth-api}`, `admin-auth/{admin-auth, admin-auth-api}`, `game/{game, game-api}`, `app`. Модули явно перечисляются в `settings.gradle.kts`.
- **Модуль зависит только от `libs/*`, `games/*` и `<other>-api` других модулей, никогда от `<other>/<other>`** (сейчас game → `user-auth-api` ради события профиля и описания таблицы `user` для чтения профилей). app зависит от всех модулей.
- **Границы проверяет Spring Modulith:** модули — прямые подпакеты `site.qgames` (`userauth`, `adminauth`, `game`, `app`), внутренние пакеты закрыты. Пакеты `-api` открыты как именованный интерфейс `api`: в каждом пакете `-api` с классами лежит `package-info.java` с `@NamedInterface("api")` (у пакета без классов аннотацию Modulith не видит). `site.qgames.common` (libs) и `site.qgames.games` (правила игр) модулями не считаются. Проверка — `ModularityTest` (`ApplicationModules.of(...).verify()`) в app.
- **Пустые модули «на вырост» не заводим.** `-api` появляется вместе с первым содержимым.
- **Имена бинов — полные имена классов** (`FullyQualifiedAnnotationBeanNameGenerator` у `@SpringBootApplication`): в модулях есть одноимённые классы (`AuthController`, `TokenService`, `RefreshTokenRepository`). Бины одного типа в разных модулях (`PasswordEncoder`, ключ и кодировщик JWT) — именованные, внедряются через `@Qualifier` с константами `CryptoConfig` модуля. Общие бины (`Clock`) объявляет только app.
- Контроллеры реализуют интерфейсы своего `<m>-api`, маршруты объявляются только в интерфейсе. Если контроллеру нужны `HttpServletRequest`/`HttpServletResponse`, они внедряются в конструктор (request-scoped прокси), а не попадают в сигнатуру контракта. SSE-ручки — тоже в интерфейсе: возвращают `ResponseEntity<ResponseBodyEmitter>` (`@GetExchange(…, accept = text/event-stream)`), контроллер отдаёт `SseEmitter` через `SseResponses.stream` (заголовки `Cache-Control: no-cache`, `X-Accel-Buffering: no`).
- В KDoc не писать `games/*`, `libs/*`, `/api/**` и т.п.: в Kotlin `/*` внутри комментария открывает вложенный комментарий и ломает компиляцию.

### Пакеты: type-sliced

Корень модуля: `site.qgames.<модуль без дефисов>` (`site.qgames.userauth`), приложения — `site.qgames.app` (`QgamesApplication` — в `site.qgames`), контракта модуля — `site.qgames.<модуль>.api.<тип>` (`api.web.AuthApi`, `api.dto.user.UserResponse`, `api.event.user.UserProfileChanged`, `api.cookie.AuthCookieNames`). Сначала тип, потом фича:

```
controller/auth/AuthController.kt      ✅
service/auth/LoginService.kt
repository/user/UserRepository.kt
table/user/UserTable.kt
model/user/UserModel.kt
mapper/user/UserRowMapper.kt
config/…, exception/…, util/…
auth/controller/AuthController.kt      ❌ (domain-sliced — не используем)
```

### Слои: таблица, модель, репозиторий

- **Таблица** (`table/<фича>/<Имя>Table`) — описание таблицы Exposed (`object UserTable : Table("user")`), ровно как в БД: перечисления — `varchar` (имена enum модели), JSON — `json<String>` (как есть), время — `timestamp` (`Instant`), UUID — `javaUUID`. Таблица живёт только в `table/`, `mapper/` и `repository/` (и в тестах). Исключение — публичное описание для чтения другими модулями в `<m>-api` (§4 «Чужие таблицы»).
- **Модель** (`model/<фича>/<Имя>Model`) — то, с чем работают сервисы, контроллеры, слушатели, джобы и паблишеры. Перечисления — enum модели с префиксом сущности (`GameCode`, `RoomStatus`, `UserAvatar`). Id не nullable: модель существует только для сохранённой строки.
- **Маппер** строка → модель — ручной `@Component` `mapper/<фича>/<Имя>RowMapper` (`ResultRow` → модель), без кодогенерации.
- **DTO** контрактов (`<m>-api`) живут только в контроллерах и их мапперах `mapper/<фича>/<Имя>ResponseMapper` (модель → DTO, DTO-enum → enum модели). Сервисы принимают и возвращают модели и примитивы, а составные ответы — композитными моделями (`RoomDetailsModel`, `AuthResultModel`). Данные для такой модели собирает сервис (`RoomDetailsService`); маппер в репозитории не ходит.
- **Репозиторий — всегда два слоя:**
  - `<Имя>Repository` (`@Repository`-класс) — единственный, кого видят сервисы. Принимает и возвращает модели и параметры (вставка — `insert(…)` с полями, id новой строки задаёт он);
  - `<Имя><Хранилище>Repository` — работа с конкретным хранилищем: `…ExposedRepository` (Exposed DSL над таблицами, строки → модели через бин-маппер). Используется только своим фасадом. Класс помечен `@Transactional`: вызов вне транзакции сервиса идёт в своей транзакции (Exposed без транзакции не работает), внутри — присоединяется к ней.
- Доменные enum'ы в DTO контрактов (`<m>-api`) — свои; контроллер (или его маппер) переводит их в enum модели по имени (`RoomRole.valueOf(request.role.name)`).

### Ошибки (`libs/common-exception`)

- Модули **не указывают `HttpStatus`**: статус задаёт класс ошибки.
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
- Обработчики подключаются автоконфигурацией при подключении библиотеки (модули подключают её явно, `common-web` — через `api`):
  - `QgamesExceptionHandler` — `QgamesException` по родительскому типу → ProblemDetail с `code` и `properties`; 400 `VALIDATION_ERROR` для `@Valid` и битого тела; ловушка `Exception` → лог ERROR и 500 `INTERNAL_ERROR` без деталей. Стандартные исключения Spring MVC (404 маршрута, 405) обрабатывает `ResponseEntityExceptionHandler`;
  - `PersistenceExceptionHandler` — `SQLException` (Exposed бросает `ExposedSQLException`): нарушение уникальности (SQLSTATE `23505` в цепочке причин) → 409 `CONFLICT`, остальное — лог ERROR и 500 `INTERNAL_ERROR`.
- Неизвестный или отсутствующий дискриминатор полиморфного DTO (`InvalidTypeIdException` Jackson) — 400 `VALIDATION_ERROR` с ошибкой по полю `errors[<путь>.<дискриминатор>]`, например `settings.gameSettings.gameCode`.
- Advice библиотеки имеют самый низкий приоритет. **Собственный advice модуля** объявляет `@Order(Ordered.HIGHEST_PRECEDENCE)`, иначе его исключения может перехватить ловушка `Exception`.
- `@Transactional(noRollbackFor = [QgamesException::class])` — когда изменение (счётчик попыток, отзыв сессии) должно сохраниться, несмотря на ошибку.

## 3. Взаимодействие

- **Фронт ↔ приложение:** REST/JSON напрямую в app (порт 8080). Ошибки — `ProblemDetail` с машиночитаемым полем `code`. Контракт — интерфейсы и DTO `<m>-api` модулей; публикуется двумя группами OpenAPI: `portal` (`/api/**` кроме `/api/admin/**`) и `admin` (`/api/admin/**`). Валидация тел — `@Valid` в контроллерах модулей.
  - Токены сессии в контракт не входят: auth-контроллеры модулей кладут их в HttpOnly-куки (§5) и отдают в теле только пользователя (`UserResponse`, `AdminResponse`). DTO с токенами (`AuthResponse`, `TokenPair`, `RefreshTokenRequest`) не заводятся — это проверяют `PortalOpenApiContractScenariosTest` и `AdminOpenApiContractScenariosTest`.
  - Одноимённые DTO разных групп (например, `LoginRequest` user-auth и admin-auth) допустимы: у каждой группы свои components. Внутри одной группы простые имена классов DTO уникальны: swagger-core называет схему по простому имени класса.
- **Документация API (Swagger):** у каждой ручки в `<m>-api` интерфейсе обязательно есть:
  - `@Operation(summary, description)` на русском: что делает метод, зачем он нужен и какие у него особенности;
  - `@ApiResponses` со всеми реально возможными статусами (200, 400, 401, …). Описания на русском, у ошибок в описании указаны значения `code` (например, «Неверный email или пароль (INVALID_CREDENTIALS)»);
  - у интерфейса — `@Tag(name, description)`:
    - `name` — латиница kebab-case (`auth`, `users`, `games`, `rooms`, `admins`) во всех модулях: из тегов Orval в режиме `tags-split` строит имена файлов клиента;
    - `description` — на русском и начинается с русского названия («Аутентификация. Вход через Google…»). Одноимённые теги разных групп (`auth` портала и админки) — с разными описаниями, у каждой группы свои теги;
    - одноимённые теги внутри группы (ручки комнат лежат в нескольких интерфейсах) — с одинаковым описанием, иначе в OpenAPI окажется несколько тегов с одним именем. В game описание общее — константы `RoomApi.TAG`/`RoomApi.TAG_DESCRIPTION`;
    - форму тегов (латиница, русское описание, тег у каждой операции объявлен) проверяют `PortalOpenApiContractScenariosTest` и `AdminOpenApiContractScenariosTest`.

  Тело ошибок (`ProblemDetail` с полем `code`) в аннотациях не указываем: его всем ответам 4xx/5xx подставляет `ProblemDetailResponsesCustomizer` из `libs/common-web`. Коды ошибок в `description` метода не перечисляем, они описываются в `@ApiResponse`. Ручки без интерфейса аннотируются прямо на контроллере. Зависимость `swagger-annotations-jakarta` подключается в `<m>-api` модули, её версия должна совпадать с той, что тянет springdoc.
- **OpenAPI для генераторов клиентов** (фронт строит клиенты через Orval по `/v3/api-docs/portal` и `/v3/api-docs/admin`):
  - группы — `GroupedOpenApi` в `OpenApiConfig` (app). Общие кастомайзеры `libs/common-web` (`ProblemDetailResponsesCustomizer`, `DiscriminatedUnionCustomizer`) — `GlobalOpenApiCustomizer`: обычный `OpenApiCustomizer`-бин springdoc к группам не применяет;
  - `required` проставляется автоматически (`KotlinRequiredPropertiesConverter` в `libs/common-web`): обязательны параметры конструктора DTO, которые не nullable и без значения по умолчанию. Исключение — параметр типа `Any?` без значения по умолчанию (любой JSON, в том числе null, например `PutData.value`): он тоже `required`. `@Schema(requiredMode)` на полях не пишем; необязательное поле — nullable или со значением по умолчанию;
  - обязательные строки запросов дополнительно помечаются `@NotBlank` (серверная проверка, 400 `VALIDATION_ERROR`). `@Size(max)` перебивает `minLength` от `@NotBlank` до 0, поэтому вместе с ним — `@Size(min = 1, max = …)`;
  - успешные ответы — `application/json` (`springdoc.default-produces-media-type` по умолчанию в `libs/common-web`), ошибки — `application/problem+json`;
  - SSE-ручка: ответ 200 — `text/event-stream` со схемой-union событий в components (`RoomStreamEvent`: конверты `{type, data}`, `oneOf` + `discriminator` по `type`). Orval не строит Zod для `text/event-stream`, фронт берёт Zod-схемы событий из components. `ResponseBodyEmitter` исключён из схем (`SpringDocUtils` в `libs/common-web`);
  - ключи документа (пути, схемы, ответы) отсортированы по алфавиту: `springdoc.writer-with-order-by-keys=true` по умолчанию в `libs/common-web`. Иначе springdoc отдаёт их в нестабильном порядке, и сгенерированный клиент на фронте меняется без изменения контракта. Проверяют `*OpenApiContractScenariosTest` в app;
  - в схемах OpenAPI 3.1 тип задаётся через `types(setOf(…))`, а не `type(…)`;
  - регулярки в `@Pattern` должны работать и в Java, и в JS (`RegExp` с флагом `u`): только Unicode-классы (`\p{Cc}`), без Java-специфичных (`\p{Cntrl}`, `\p{javaLetter}`);
  - **полиморфные DTO** (например, настройки конкретной игры) оформляются так:
    - sealed-класс с `@JsonTypeInfo(EXISTING_PROPERTY)` и `@JsonSubTypes`;
    - `@Schema(oneOf, discriminatorProperty, discriminatorMapping)`;
    - дискриминатор — параметр конструктора с `@field:Schema(type = "string", allowableValues = [...])`.

    Чистый `oneOf` + `discriminator` без цикла `allOf` от swagger-core даёт `DiscriminatedUnionCustomizer` из `libs/common-web`. Форму схемы проверяет `PortalOpenApiContractScenariosTest`. `jackson-annotations` (версия из BOM) подключается в `<m>-api`.

    Дискриминатор настроек игры (`gameSettings`, `gameSettingsLimits`) — поле `gameCode`, значения — коды игр (`nothing`), константы из `GameCodes`.
- **Код игры — единственный идентификатор игры.** Enum `GameCode` в контрактах сериализуется строчным кодом (`@get:JsonValue val code`), поэтому в JSON и в OpenAPI значения — `nothing`, `carcassonne`, `checkers`, а не имена констант. Отдельного «типа игры» нет.
- **Код игры — в пути.** Ручка, которой нужна игра, живёт под `/api/games/{gameCode}/...`, сначала `games`, потом `rooms`:
  - каталог игр — `GET /api/games`, игра с параметрами правил — `GET /api/games/{gameCode}`;
  - создание комнаты — `POST /api/games/{gameCode}/rooms`;
  - настройки — `PUT …/rooms/{roomId}/settings`;
  - ход — `POST …/rooms/{roomId}/moves`.

  `{gameCode}` — строка с `allowableValues`. Код игры в теле запроса не передаётся. Неизвестный код — 404 `GAME_NOT_FOUND` (контроллеры game, `GameCode.fromCode`). Если код не совпадает с игрой комнаты — 409 `GAME_TYPE_MISMATCH`. Ручкам без привязки к игре (комната, вход, роль, партия) код не нужен.
- **Каталог игр доступен гостям:** `GET /api/games` и `GET /api/games/{gameCode}` — permitAll в цепочке портала (только GET и только сами игры, комнаты требуют входа). Контроллер каталога пользователя не читает. Гость с протухшей access-кукой получает на этих ручках 401 (Bearer-фильтр проверяет токен и на permitAll) — принято осознанно, 401 описан в `@ApiResponses`.
- **Пользователь запроса:** `RequestUserContext.userId()` (`libs/common-web`) — `sub` проверенного access-JWT из `SecurityContext` (в цепочке админки — id администратора). В сигнатурах `<m>-api` userId нет. Заголовка `X-User-Id` нет.
- **Модуль ↔ модуль (синхронно):** вызов бина через интерфейс из `<m>-api` (заводится вместе с первой такой потребностью). Чужие таблицы, репозитории и сервисы не используются.
- **Побочные эффекты между модулями — события модулей:** событие — Kotlin data class в `<m>-api` (`api.event.<фича>`), публикуется `ApplicationEventPublisher` в транзакции бизнес-изменения, принимается `@TransactionalEventListener(AFTER_COMMIT)` (синхронно после коммита; если слушателю нужна БД — своя транзакция `REQUIRES_NEW`). Реестра публикаций нет: событие, не доставленное из-за падения процесса между коммитом и слушателем, теряется — слушатели только рассылают уведомления, данные не копируют. Подробности — в §4 «События модулей».
- **Real-time в играх:** REST (ходы и действия) + **SSE** (события).
  - **Цепочка:** браузер → app, поток отдают контроллеры game (`SseEmitter`, заголовки `Cache-Control: no-cache`, `X-Accel-Buffering: no`). JWT из куки проверяется **только при подписке**. Ошибки до старта потока — обычный ProblemDetail. Что отдавать каждому подключению, решает game.
  - **Рассылка событий потокам** — Postgres `LISTEN/NOTIFY` (каналы `room_events`, `session_events`). `RoomEventPublisher` и `SessionEventPublisher` делают `pg_notify` (`PgNotifier`) в транзакции изменения, а Postgres доставляет уведомление только после коммита и в порядке коммитов, поэтому outbox не нужен. В уведомлении только сигнал (`{roomId, type}`, `{sessionId, fromSeq, toSeq}`), данные диспетчер читает сам. `RoomEventListener` держит отдельное соединение вне пула, при простое проверяет его (`isValid`), а при обрыве переподключается и закрывает все потоки — клиенты переподключатся и получат свежий снапшот.
  - **Подписки комнаты и партии — разные сущности.** Поток комнаты — `GET /api/rooms/{roomId}/events`: первым сообщением `snapshot` (как `GET` комнаты), затем `users-changed` (`ownerId` + полный список участников), `settings-changed`, `session-changed` (данных нет, клиент перечитывает комнату), `room-deleted` (поток закрывается). У событий комнаты нет seq: они несут полное значение (замена), повтор безопасен. Гонку снапшота с событиями закрывает маркер: подписчик регистрируется до транзакции подключения, в ней под `lock(room)` читается снапшот и публикуется маркер подписчика; всё полученное до маркера отбрасывается. Ping (`: ping`) раз в 5 с.
  - **Поток партии** — `GET /api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events` (`GameRoomApi.sessionEvents`). Подписаться может участник комнаты (в том числе отсутствующий игрок партии); `sessionId` чужой комнаты — 404 `SESSION_NOT_FOUND`. Аренды и присутствия у него нет.
    - Первый кадр — `snapshot` без обёртки: `{seq, status, serverTime, meta, state}` (`state` — свёртка маскированных событий зрителя). Остальные — `event:` = метод в нижнем регистре (`put`/`patch`/`delete`/`info`), `data:` = `{seq, at, method, code, data}`, где `data` — `{path, value}` (PUT/PATCH), `{path}` (DELETE) или объект (INFO); seq монотонный у каждого события журнала; замаскированные события тоже доставляются, поэтому дыра в seq — реальная потеря.
    - Подписчик регистрируется до снапшота: события с `seq ≤ N` снапшота отбрасываются, пришедшие раньше снапшота копятся. Маркер не нужен.
    - `session-finished`/`session-cancelled` закрывают поток. Подписка на завершённую партию — снапшот `Full` и закрытие (так вошедший видит последнюю партию).
    - Рассылка — `pg_notify('session_events', {sessionId, fromSeq, toSeq})`; диспетчер читает события из журнала один раз и маскирует один раз на проекцию. `RoomEventListener` слушает оба канала и при обрыве закрывает оба реестра.
    - OpenAPI: union `SessionStreamEvent` по `type` (`SessionSnapshotEvent` и `SessionPatchStreamEvent`), внутри — `SessionPatchEvent` по `method` (`SessionEventPut/Patch/Delete/Info`); `PathSegment` = `string | {id}`; `code` — строка без enum (известные коды — в описании). `value` не типизирован: строго типизированы только состояние у снапшота (`SessionStateDto` — `oneOf` схем игр) и `meta.moveRules` (`SessionMoveRulesDto` — `oneOf` правил хода игр, `null` у игры без них), оба без дискриминатора: игра определяется по `meta.game.code`, и по нему `SessionEventResponseMapper` разбирает их строго.
  - Общая инфраструктура потоков: `SseWriter` (очередь и поток записи на подписчика), `StreamIndex`, `SseResponses`.
- **WebSocket** — только для будущих игр с real-time перемещением игроков (сейчас таких нет).

## 4. Данные

- **PostgreSQL 18, одна БД `qgames` и одна роль `qgames` на приложение** — их создаёт сам образ из `POSTGRES_USER`/`POSTGRES_DB` на пустом томе (отдельного init-скрипта нет). Модуль пишет только в свои таблицы.
- **Доступ к данным — Exposed DSL** (последний стабильный 1.x, без DAO) в транзакциях Spring: `@Transactional` остаётся, менеджер транзакций — `SpringTransactionManager` Exposed (`exposed-spring-boot4-starter` в app). JPA/Hibernate и Spring Data JDBC не используются.
- **Без сырого SQL:** доступ к БД — только через Exposed DSL, миграции — только абстрактные изменения Liquibase, диалектных функций нет. **Единственное исключение — `pg_notify` (`PgNotifier`) и `LISTEN` (`RoomEventListener`)**: у Exposed нет DSL для LISTEN/NOTIFY. SQL-фикстуры тестов — тестовые данные, не доступ из кода. Поэтому без `RETURNING` (выборка и изменение одних строк — в одной транзакции), без CHECK-ограничений и частичных индексов (их нет среди абстрактных изменений Liquibase open source: значения проверяет код через enum).
- **Пул Hikari — 5 соединений** (`minimum-idle` 2) плюс одно соединение `RoomEventListener` вне пула: один инстанс, запросы короткие, а каждое соединение — отдельный процесс PostgreSQL.
- **Блокировки:** `forUpdate()` Exposed там, где нужна блокировка строки (`lock(room)` — `SELECT … FOR UPDATE`). `SKIP LOCKED` не используется (один инстанс).
- **Именование:** таблицы в единственном числе, snake_case, без префиксов модулей; **имя таблицы уникально в приложении**, при совпадении — уточнение по смыслу (`user_refresh_token`, `admin_refresh_token`). Индексы и ограничения — с именем таблицы, имена уникальны в БД: `user_refresh_token_hash_uq`, `room_empty_since_idx`, `room_member_pk`, `room_session_room_fk`. `user` — зарезервированное слово PostgreSQL: Exposed и Liquibase экранируют его сами, в SQL-фикстурах — `"user"`.
- **Чужие таблицы — только чтение и только через описания из `<m>-api`:** модуль, которому нужны данные другого модуля, соединяет свои таблицы с его таблицей (join) через публичное описание Exposed в `<m>-api` (`api.table.<фича>`, только нужные колонки). Писать в чужие таблицы нельзя; полное описание таблицы — в реализации модуля-владельца, публичное — подмножество его колонок. Сейчас: `UserProfileTable` (`id`, `nickname`, `avatar` таблицы `user`) в `user-auth-api`, его читает game.
- **ID:** UUID v7, генерирует приложение (`Uuid.generateV7()` Kotlin, `util/Ids.kt` модуля), не БД. Тип — родной `uuid` (`javaUUID` в Exposed, `type: uuid` в миграциях). Строки каталога игр (DML) — с фиксированными UUID.
- **Время:** колонки `timestamptz` (`type: timestamp with time zone` в миграциях), в Exposed — `timestamp()`, в коде `Instant` из бина `Clock`. pgjdbc передаёт значение со смещением JVM, поэтому момент сохраняется верно при любом часовом поясе JVM; пояс не меняем. Точность — микросекунды.
- **JSON** (настройки игры, данные событий журнала) — `json<String>` Exposed: строка JSON как есть (Jackson в мапперах), тип `json` (не `jsonb`: текст хранится как записан, порядок ключей сохраняется).

### События модулей

- **Источник** (сейчас user-auth): событие — Kotlin data class в `<m>-api` (`api.event.<фича>`), публикуется `ApplicationEventPublisher` (`UserProfilePublisher`, `@Transactional(MANDATORY)`) в транзакции бизнес-изменения. Сейчас — `UserProfileChanged(id, nickname, avatar)` при создании аккаунта и каждой смене профиля; сохранение без изменений события не публикует. Правки профиля — «последняя запись побеждает», версии строки нет.
- **Потребитель** (сейчас game): `@TransactionalEventListener(AFTER_COMMIT)` на тип события из `<m>-api` источника, синхронно после коммита источника; читает БД в своей транзакции (`REQUIRES_NEW`). Данные источника потребитель не копирует: сами данные он читает из таблицы источника через описание из `<m>-api`. `UserProfileListener` game рассылает `users-changed` во все комнаты пользователя.

### Каталог игр (модуль `game`)

- Игры объявляются в таблице `game` (`code`, `title`, `description`, `sort_order`, `publish_status`) DML-миграциями (`<дата>-INSERT-game`). `room.game_code` и `room_session.game_code` ссылаются на `game(code)`. Статус позже будет менять админка.
- Набор кодов в таблице совпадает с enum `GameCode`, модуль правил обязателен для всех игр, кроме `NOT_PUBLISHED`. Это проверяет `GameCatalogVerifier` при старте приложения (после миграций); при расхождении приложение не стартует.
- **Статусы публикации** (`publish_status`, значения — enum `GamePublishStatus`) — три. Наружу статус не выходит (в публичных ответах поля нет): в каталоге и по прямой ссылке видны только доступные игры, поэтому `rules` в `GameDetailsResponse` всегда есть:

  | Статус | В `GET /api/games` | `GET /api/games/{gameCode}`, комнаты, партии | Модуль правил |
  |---|---|---|---|
  | `PUBLISHED` | да | работают | обязателен |
  | `HIDDEN` | нет | работают (доступ по прямой ссылке) | обязателен |
  | `NOT_PUBLISHED` | нет | 404 `GAME_NOT_FOUND`; `NotPublishedGameCleanupJob` отменяет идущие партии и удаляет комнаты | может отсутствовать |

  `NOT_PUBLISHED` — игра в разработке или отключена; отключение игры — штатный перевод в `NOT_PUBLISHED`.
- Доступность игры проверяет `GameService.checkAccessible` — при создании комнаты (до проверки настроек), старте партии и во всех остальных операциях с комнатой, по игре комнаты под её блокировкой.
- **`NotPublishedGameCleanupJob`** (`qgames.game.catalog.not-published-cleanup-enabled`, интервал 1m): пачкой находит комнаты `NOT_PUBLISHED`-игр, каждую — под блокировкой комнаты с перепроверкой статуса, отменяет идущую партию и удаляет комнату (участники и партии — каскадно). `MoveTimeoutJob` и `RoomOwnerGraceJob` комнаты `NOT_PUBLISHED`-игр пропускают (фильтр в SQL).
- Решить при реализации управления каталогом игр в админке:
  - перевод в `PUBLISHED`/`HIDDEN` разрешён только игре с модулем правил — проверка в ручке смены статуса (сейчас статус меняется миграциями, а проверяет `GameCatalogVerifier`; игра без модуля правил наружу и так не выдаётся — 404);
  - завершение партий и удаление комнат при смене статуса вызовом из админки и судьба периодического `NotPublishedGameCleanupJob`.

### Игровые комнаты (модуль `game`)

- **Публичные комнаты без поиска:** вход по ссылке с id комнаты для любого авторизованного пользователя. Приватные комнаты и поиск — решить при реализации.
- Комната создаётся под игру (`room.game_code` → `game.code`); создатель становится владельцем и первым игроком, когда подключится к её потоку (см. «Создатель комнаты»).
- **Вход:**
  - игрок идущей партии возвращается на своё место (PLAYER);
  - иначе, если партия не идёт и есть место, — PLAYER;
  - в остальных случаях — SPECTATOR;
  - повторный вход ничего не меняет.
- **Присутствие = открытый SSE-поток комнаты.** Ручек входа и выхода нет. Открытие потока — вход, закрытие последнего потока пользователя в комнате — выход. Подключений у пользователя может быть несколько (вкладки, устройства), каждое — строка `room_connection` с арендой (`lease` 20 с). `RoomConnectionHeartbeatJob` раз в 5 с шлёт ping в потоки и продлевает аренду их подключений (выборка существующих id и `UPDATE` в одной транзакции); поток, чьё подключение не продлилось (его удалил cleanup), закрывается — клиент переподключится и войдёт заново. `RoomConnectionCleanupJob` удаляет протухшие подключения (процесс упал или потерял БД) и уводит пользователей без подключений. Обрыв клиента замечается только по неудачной записи ping, обычно за 10–30 с.
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
- **Автоудаление:** комната без присутствующих (подключённых) участников дольше `qgames.game.room.empty-ttl` (30m) удаляется `EmptyRoomCleanupJob`. Отсутствующие игроки партии комнату не держат. Джоб пачкой выбирает id, каждую комнату берёт под блокировкой с перепроверкой, отменяет идущую партию и удаляет комнату (участники, подключения и партии — каскадно).
- **Конкурентность:** каждая мутация комнаты, участников и партии идёт под блокировкой комнаты (`RoomRepository.lockById`, `SELECT … FOR UPDATE` строки `room`). Джобы (`MoveTimeoutJob`, `RoomConnectionCleanupJob`, `RoomOwnerGraceJob`, `EmptyRoomCleanupJob`, `NotPublishedGameCleanupJob`) выбирают id без блокировки, затем каждую комнату обрабатывают в своей транзакции под её блокировкой с перепроверкой.
- **Real-time:** изменения комнаты идут по SSE-потоку комнаты, состояние партии — только по потоку партии (§3). В ответе комнаты партия краткая: `session {id, status}`; `session-changed` в потоке комнаты — на старт, конец и отмену партии.
- Смена ника (`UserProfileListener`) публикует `users-changed` во все комнаты пользователя **без блокировки комнат**: если из-за гонки со снапшотом кто-то не увидит новый ник, это допустимо.

### Сессии комнаты — партии (модуль `game`)

- Партия называется **сессией комнаты** (`room_session`, в коде `RoomSession*`). Таблицы общие для всех игр, своих таблиц у игры нет:
  - `room_session`: код игры (`game_code` → `game.code`), статус, `state_schema_version`, `seed`, `last_seq`, снапшот настроек комнаты, `move_deadline` (проекция таймера для индекса `MoveTimeoutJob`: дедлайн идущего таймера, на паузе NULL);
  - `room_session_player`: места игроков партии;
  - `room_session_event`: **журнал событий** (`session_id`, `seq`, `method`, `code`, `data` (JSON) в форме кадра, `created_at` — время события для повтора). Состояние партии **не хранится**: это свёртка журнала.
- **Состояние партии** собирает `SessionStateService` через абстракцию `SessionStateCache` (Caffeine): ключ `(sessionId, viewerKey) → (seq, state)`. Берётся закешированное состояние на seq K, дочитываются события `K+1…N`, результат кладётся обратно; промах — свёртка с нуля. Журнал только дописывается, поэтому инвалидация не нужна. Новое полное состояние попадает в кеш только после коммита.
- **Любое изменение партии** (старт, ход, таймаут, пауза, продолжение, конец, отмена) идёт через `SessionJournalService` под `lock(room)`: полное состояние на `last_seq` → события → `INSERT` в журнал с seq от `last_seq + 1`, обновление `last_seq`, статуса, `move_deadline` → событие шины. На одно изменение может прийтись несколько событий, каждое со своим seq.
- **Мутации партии** (ход, пауза, продолжение, отмена) отвечают **204**: результат приходит событиями потока. Старт возвращает комнату.
- **Ручка хода одна на все игры:** `POST /api/games/{gameCode}/rooms/{roomId}/moves`.
  - `gameCode` — строчный код игры (`GameCode.code`: `nothing`, `carcassonne`, `checkers`). Неизвестный код — 404 `GAME_NOT_FOUND`, не совпадает с игрой комнаты — 409 `GAME_TYPE_MISMATCH`.
  - Тело — общий конверт `{type, data}` (тот же конверт, что варианты в `moveOptions`): `type` — вид действия (`PASS`, `MOVE`, `RESIGN`), `data` — его параметры в формате игры. Сервис превращает `{type, data}` в класс хода игры (`GameRules.moveClass`); неподходящий формат — 400 `VALIDATION_ERROR`.
  - Своих ручек у игр нет. `MoveRequest.data` в OpenAPI остаётся свободным объектом, а схемы параметров ходов игр (`CheckersMoveDataDto {path}`) перечислены в `MoveDataSchemas.CLASSES` (`game-api`, `api.dto.session`) и публикуются в components группы `portal` кастомайзером группы в `OpenApiConfig` (app): ни одна ручка на них не ссылается, поэтому springdoc сам их не выдаёт. Схемы строятся тем же набором конвертеров (`required` по Kotlin), клиент типизирует по ним `data` своей игры.
- Ход проверяется по текущему полному состоянию под блокировкой комнаты, **без проверки версии**: если хода нет среди допустимых — 422 `MOVE_REJECTED` с машиночитаемой причиной в поле `reason` (например, `NOT_YOUR_TURN`).
- **Таймер хода** (режим `PER_MOVE`, модуль `timer`):
  - старт и каждый ход ставят дедлайн `now + moveSeconds` (`timer-started`), конец и отмена партии останавливают таймер (`timer-stopped`, `STOPPED`);
  - `MoveTimeoutJob` раз в секунду обрабатывает истёкшие дедлайны по проекции `move_deadline`. Выборка идёт без блокировки, затем на каждую партию — блокировка комнаты с перепроверкой, в отдельной транзакции;
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
<m>/<m>/src/main/resources/db/changelog/<модуль>/      changelog'и модуля (папка — имя модуля: user-auth, admin-auth, game)
  db.changelog-master.yaml                                явный список include в хронологическом порядке (DDL и DML вперемешку)
  DDL/2026-10-17-CREATE-user.yaml                         changeSet: id, author, runInTransaction, comment, абстрактные changes
  DML/2026-10-18-INSERT-game.yaml                         insert + явный rollback
app/src/main/resources/db/changelog/
  db.changelog-master.yaml                                сводный master приложения: include master-файлов модулей
```

- Таблица `databasechangelog` одна на приложение. Пути changelog'ов модулей не пересекаются (папка модуля), поэтому одноимённые файлы разных модулей не конфликтуют.
- **Имя миграции:** `<yyyy-MM-dd>-<ДЕЙСТВИЕ>-<таблица>` без префикса модуля (файл лежит в папке модуля), например `2026-10-17-CREATE-user_identity`, `2026-11-02-ALTER-user`, `2026-11-02-INSERT-game`. Действие капсом: `CREATE`, `ALTER`, `DROP` (DDL), `INSERT`, `UPDATE`, `DELETE` (DML). Имя одновременно служит `id` changeSet'а. Если в один день над одной таблицей нужно два одинаковых действия — добавить уточнение в конец (`2026-11-02-ALTER-user-add_nickname`).
- **Одна таблица — одна миграция.** Индексы и ограничения таблицы входят в её CREATE-миграцию.
- **DDL** (структура) и **DML** (данные) лежат в разных папках. Порядок выполнения задаёт только master: файлы перечисляются явно, `includeAll` не используем.

- **Изменения — только абстрактные** (`createTable` с колонками и ограничениями, `createIndex`, `insert`, `update`, `delete`…), без `sqlFile` и `sql`. Ограничения задаются в колонках `createTable`: `primaryKey` + `primaryKeyName` (составной ключ — тем же именем у нескольких колонок), `unique` + `uniqueConstraintName`, внешний ключ — `foreignKeyName`, `referencedTableName`, `referencedColumnNames`, `deleteCascade`. Составная уникальность — `createIndex` с `unique: true`. CHECK и частичных индексов среди абстрактных изменений нет.
- Типы: `uuid`, `timestamp with time zone`, `json`, `varchar(n)`, `text`, `integer`, `bigint`; значения по умолчанию — только константы (`defaultValueNumeric`), время и id задаёт приложение.
- yaml-changelog шаблон:
  ```yaml
  databaseChangeLog:
    - changeSet:
        id: 2026-10-17-CREATE-user
        author: A Kozlov            # git config user.name автора миграции
        runInTransaction: true
        comment: Аккаунты игроков.
        changes:
          - createTable:
              tableName: user
              columns:
                - column:
                    name: id
                    type: uuid
                    constraints:
                      primaryKey: true
                      primaryKeyName: user_pk
  ```
- **Rollback:** у `createTable`/`createIndex` Liquibase выводит его сам; явный `rollback` обязателен там, где не выводит (DML: `insert` → `delete` с условием).
- **Пока проект нигде не используется** (нет прода и общих стендов с данными), ALTER-миграции не заводим: правится исходная CREATE-миграция таблицы, даже закоммиченная, а локальная БД пересоздаётся (`docker compose down -v`).
- С появлением прода или общего стенда закоммиченные миграции не редактируются, изменения идут только новыми миграциями.

## 5. Аутентификация и безопасность

- **Две цепочки Security в app** (`SecurityConfig`):
  - админка — `/api/admin/**` (`securityMatcher`, `@Order(1)`): кука `admin_access_token` или `Authorization: Bearer`, токены admin-auth (свой ключ, issuer `qgames-admin-auth`); permitAll — `/api/admin/auth/**`;
  - портал — всё остальное (`@Order(2)`): кука `access_token` или `Authorization: Bearer`, токены user-auth (issuer `qgames-user-auth`); permitAll — `/api/auth/**`, `GET /api/games` и `/api/games/*`, Swagger (`/swagger-ui*`, `/v3/api-docs/**`), `/actuator/health/**`.
  - Токен игрока в админку не проходит, токен админа на портал — тоже (разные ключи и issuer). Без токена или с недействительным — 401 `UNAUTHENTICATED` (ProblemDetail). На эндпоинтах аутентификации access-токен не читается (`CookieBearerTokenResolver`): протухшая кука не ломает refresh.
  - JWT проверяется локально по **публичной части ключа подписи из конфигурации** (`qgames.security.{portal,admin}.public-key` → те же PEM, что у модулей), без JWKS-эндпоинта: `NimbusJwtDecoder` с `SingleKeyJWSKeySelector` (`withPublicKey` в Spring Security принимает только RSA). Проверяются подпись ES256, срок и issuer, `kid` не сверяется.
- `user-auth` подписывает access-JWT игроков (**ES256**, TTL 15 мин, `sub` = userId).
- **Refresh-токен:** случайная строка, в БД — только SHA-256; TTL 30 дней; ротация при каждом обновлении.
  - **Окно терпимости 10 с** (`qgames.auth.refresh-reuse-grace`). Повтор уже ротированного токена в течение окна, если в семье есть активный токен, выдаёт ещё одну пару той же сессии. Так обновляют сессию параллельные вкладки.
  - Повтор позже окна, после logout или после обнаружения кражи отзывает всё семейство (`family_id`) с ошибкой `REFRESH_TOKEN_REUSED`.
  - Ротацию от прочих отзывов отличает `rotated_at` (`user_refresh_token`, `admin_refresh_token`).
- **Сессия админа (`admin-auth`):**
  - свой ключ подписи ES256 и issuer `qgames-admin-auth`;
  - access — 5 мин, refresh — 15 мин. Refresh скользящий: каждая ротация даёт полный TTL. Окно терпимости 10 с (`qgames.admin-auth.refresh-reuse-grace`), ротация и отзыв семьи — как у user-auth;
  - логин сравнивается точно, без lowercase и trim. При неизвестном логине проверяется dummy-хеш, чтобы время ответа не выдавало существующие логины.
- **Первый админ:** регистрации нет. При старте приложения `AdminBootstrapService` создаёт админа из `ADMIN_BOOTSTRAP_LOGIN` / `ADMIN_BOOTSTRAP_PASSWORD`, только если таблица `admin` пуста.
  - Проверка «таблица пуста» и вставка — в одной пишущей транзакции (`AdminExposedRepository.insertIfNoAdmins`). Если админ создан, пишется лог WARN.
  - Значений по умолчанию в `application.yml` нет: без env бутстрап выключен, а если задано только одно из двух, приложение не стартует. docker-compose задаёт `admin` / `admin`.
- **Куки ставят и читают auth-контроллеры модулей** (`cookie/AuthCookies` в user-auth и admin-auth): `HttpOnly; Secure; SameSite=Lax`. Токены в теле ответов не отдаются.
  - Имена и пути — константы контракта `AuthCookieNames` в `<m>-api`: портал — `access_token` (`Path=/`) и `refresh_token` (`Path=/api/auth`); админка — `admin_access_token` (`Path=/api/admin`) и `admin_refresh_token` (`Path=/api/admin/auth`). Имена разные: оба фронта ходят в одно приложение, и одноимённые куки перетёрли бы друг друга. `secure`/`sameSite` — свойства модулей (`qgames.auth.cookies`, `qgames.admin-auth.cookies`, env `COOKIES_SECURE`).
  - refresh без куки или с отклонённым токеном (401) удаляет обе куки; logout отзывает сессию по куке и всегда удаляет обе куки; смена пароля выдаёт новую сессию в куках.
- CSRF-токены не используются (SameSite=Lax + только JSON). Сессий нет (stateless).
- **Домены:** фронт и API — поддомены одного домена (например, `qgames.site` и `api.qgames.site`). Для браузера это same-site, поэтому Lax-куки доходят до API и защита от CSRF без токенов остаётся валидной. Если фронт окажется на другом домене, это пересмотр модели CSRF, только по согласованию. Same-origin (Caddy) — решить при этапе 4 перехода на монолит.
- **CORS** настраивается в app (`SecurityConfig.corsConfigurationSource`):
  - `/api/admin/**` — только origin админки (`ADMIN_CORS_ALLOWED_ORIGINS`, по умолчанию `http://localhost:3001`); остальные `/api/**` — только origin портала (`CORS_ALLOWED_ORIGINS`, по умолчанию `http://localhost:3000`). Портал в админку и админка в портал не пускаются;
  - явный список origin, wildcard запрещён и проверяется при старте (`CorsProperties`);
  - `allowCredentials = true`;
  - запросы с чужого origin отклоняются с 403.

- **Пароли:** Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`), соль внутри хеша.
- **Регистрации по email нет, писем user-auth не шлёт.** Аккаунт создаётся только входом через Google. Пароль добавляется в профиле (`POST /api/users/me/login-methods/password`), после этого можно входить по email аккаунта (это email Google-аккаунта, которым он создан) и паролю. Сброса пароля нет: забывший пароль входит через Google и меняет его.
- **Google:** фронт получает ID token через Google Identity Services и шлёт `POST /api/auth/google`; проверяем подпись по JWKS Google, `iss`, `aud` (`GOOGLE_CLIENT_IDS`).
- **Профиль игрока:**
  - ник (3–32 символа, без пробелов по краям, **не уникален** — игроки различаются по id) и аватар из enum `Avatar { CAT, DOG }`, набор аватаров определён на фронте;
  - новому Google-аккаунту генерируется ник `player-xxxxxx` и аватар `CAT`;
  - меняются через `PATCH /api/users/me/profile` (только переданные поля);
  - создание аккаунта и каждое изменение публикуются событием модуля `UserProfileChanged` (§4 «События модулей»); правки — «последняя запись побеждает».
- **Способы входа:** `user_identity(provider, subject)`, к одному аккаунту можно привязать несколько. Привязка **только явная** (из залогиненной сессии, `/api/users/me/login-methods/*`), автопривязки нет.
- **Вход через Google** (`POST /api/auth/google`): Google-аккаунт привязан (по `subject`) — сессия его владельца; иначе email не подтверждён в Google — 400 `GOOGLE_EMAIL_NOT_VERIFIED` (email — логин для входа по паролю); иначе аккаунт с таким email уже есть — 409 `ACCOUNT_LINK_REQUIRED` (защитный случай, email в БД уникален); иначе — новый аккаунт.
- Ключи подписи — из конфигурации/секретов, никогда не генерируются при старте. `docker/dev-keys` — только для локальной разработки: `jwt-*.pem` у user-auth (env `JWT_*`), `admin-jwt-*.pem` (kid `admin-dev-key-1`) у admin-auth (env `ADMIN_JWT_*`).

## 6. Один инстанс

Одно приложение, один инстанс. Несколько инстансов app не поддерживаются: в памяти процесса живут реестры SSE-потоков и кеш состояний партий, ShedLock не нужен.

- Состояние в памяти процесса допустимо только такое:
  - реестры открытых SSE-потоков (`RoomStreamRegistry`, `SessionStreamRegistry`): привязаны к соединениям, а присутствие хранится в БД (`room_connection` с арендой);
  - кеш состояний партий `SessionStateCache` (Caffeine): только ускоряет сборку, источник правды — журнал, состояние на seq неизменно.
- Счётчики, лимиты, блокировки — в БД (атомарные `UPDATE … WHERE …`). Гонки на уникальных ключах → 409 (`PersistenceExceptionHandler`).
- **Присутствие — аренды в БД** (`room_connection`): приложение продлевает аренду живых потоков, а подключения, которые никто не продлевает (процесс упал или перезапущен, отключение не удалось), удаляет по истечении аренды `RoomConnectionCleanupJob`.
- Шедулеры (`@EnableScheduling` на `QgamesApplication`): джобы выбирают id без блокировки, затем каждую комнату или партию обрабатывают в своей транзакции под блокировкой комнаты с перепроверкой. У каждого джоба свой флаг включения `…-enabled`. В тестах джобы выключены и вызываются напрямую через `run()`.

## 7. Тесты

- Тесты **максимально интеграционные**: запросы через MockMvc в настоящие контроллеры на полном контексте приложения (все модули, настоящие цепочки Security и куки), реальная PostgreSQL (Testcontainers), внешние HTTP-системы (JWKS Google) — WireMock. Тривиальных тестов не пишем.
- **Все интеграционные тесты живут в `app/src/test/kotlin`** в пакетах модулей (`site.qgames.game.controller.room…`, `site.qgames.userauth…`); тесты Security, CORS, кук и OpenAPI — в `site.qgames.app…`; границы модулей — `ModularityTest`. Чистые тесты правил игр — в своих модулях `games/*`.
- Один `site.qgames.BaseIntegrationTest`: в нём все `@MockkSpyBean`, поэтому контекст Spring один на прогон. Модульные базы (`UserAuthIntegrationTest`, `AdminAuthIntegrationTest`, `GameIntegrationTest`) наследуют его и добавляют только хелперы и `@Autowired` — новые `@MockkSpyBean` объявляются только в общей базе. Внешние системы (WireMock) сбрасываются в `@BeforeEach`.
- **Пользователь в тестах** — настоящий access-JWT, подписанный тестовым ключом модуля (`userAccessToken`, `adminAccessToken`, `asUser`, `asAdmin` в базе). Сессии открываются через настоящие ручки, токены читаются из кук ответа.
- **События модулей в тестах:** у источника — опубликованные события через `ApplicationEvents` (`@RecordApplicationEvents`); у потребителя — сквозной сценарий через ручку источника (смена профиля → `users-changed` в потоках комнат и новый ник в снапшоте комнаты).
- **Состояние БД в тестах** читается и меняется через Exposed DSL и таблицы модулей (`db { … }`, `count(table) { … }`, `snapshot(table)` в `BaseIntegrationTest`), без сырого SQL.
- **Тестовые данные:** `app/src/test/resources/init/<модуль>.sql` выполняются перед каждым тестом (`@Sql` на `BaseIntegrationTest`): `TRUNCATE … CASCADE` всех таблиц модуля и вставка базовых фикстур с фиксированными UUID. Константы фикстур — в `support/TestData.kt` модуля и совпадают с его init-скриптом. Новая таблица добавляется в TRUNCATE скрипта своего модуля. Строки таблицы `user` для игроков тестов game — тоже в `init/user-auth.sql` (владелец таблицы — user-auth).
- Тест, которому нужен готовый пользователь или сущность, берёт фикстуру, а не создаёт её через API. Через API идут только сценарии, которые проверяют сам процесс создания (например, создание аккаунта входом через Google).
- Предпочитаем **параметризованные сценарные тесты** (`@ParameterizedTest` + `@MethodSource`), состояние проверяем в БД.
- Контейнеры — singleton на прогон (`libs/common-test`), чтобы контекст Spring переиспользовался между классами.
- Для запуска тестов нужен Docker.

## 8. Стек и версии

Kotlin 2.4, JDK 25+ (сборка на любом JDK ≥ 25 без toolchain, байткод — Java 25), Gradle 9.6 (configuration cache включён), Spring Boot 4.1 (Spring MVC + virtual threads, Jackson 3), Spring Modulith 2.1 (свой BOM), springdoc 3.1, Exposed 1.6 (свой BOM, `exposed-spring-boot4-starter`), PostgreSQL 18, Liquibase, Testcontainers 2, springmockk 5. Версии — в `gradle/libs.versions.toml`.

## 9. Локальный запуск

```bash
./gradlew build                       # сборка + тесты (нужен Docker)
docker compose up --build -d --wait   # весь проект: postgres + app
docker compose down -v                # остановить и удалить данные
```

- Приложение — http://localhost:8080: Swagger — http://localhost:8080/swagger-ui.html (группы `portal` и `admin`), OpenAPI — `/v3/api-docs/portal`, `/v3/api-docs/admin`.
- Вход в админку на стенде: `admin` / `admin`.
- Google-вход: заполнить `GOOGLE_CLIENT_IDS` в `.env` (см. `.env.example`).
- Запуск из IDE: `docker compose up -d postgres`, затем `QgamesApplication` (или `./gradlew :app:bootRun`) с рабочим каталогом `app/`: значения по умолчанию в `application.yml` смотрят на localhost и `../docker/dev-keys`.
- Переменные окружения app: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `JWT_KEY_ID`/`JWT_PRIVATE_KEY_LOCATION`/`JWT_PUBLIC_KEY_LOCATION` (игроки), `ADMIN_JWT_KEY_ID`/`ADMIN_JWT_PRIVATE_KEY_LOCATION`/`ADMIN_JWT_PUBLIC_KEY_LOCATION` (админы), `GOOGLE_CLIENT_IDS`, `ADMIN_BOOTSTRAP_LOGIN`/`ADMIN_BOOTSTRAP_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `ADMIN_CORS_ALLOWED_ORIGINS`, `COOKIES_SECURE`.
- Образ: multi-stage `docker/app.Dockerfile` (`:app:bootJar`). Контекст сборки — корень репозитория.

## 10. Развёртывание (один VPS)

На старте всё работает на одном VPS через docker compose, без k8s: PostgreSQL + одно приложение.

- **Образы** собираются в CI и пушатся в registry, на VPS только `docker compose pull`. Gradle-сборка на VPS съела бы 2–3 ГБ RAM рабочего сервера.
- **Наружу** открыт только reverse proxy с TLS, он проксирует в app. Swagger в проде не публикуется.
- **Память:** лимит памяти контейнера app (`mem_limit`) — решить при деплое на VPS 1 ГБ (этап 4 перехода на монолит). Heap считается от лимита через `-XX:MaxRAMPercentage` (уже в `docker/app.Dockerfile`), без лимита JVM ориентируется на всю память хоста.
- **PostgreSQL под малую память** (`command: -c …` в `docker-compose.yml`, `mem_limit: 128m`): `max_connections=20` (пул 5 + LISTEN + миграции, psql и запас), `shared_buffers=16MB` (горячие данные малы, остальное — кеш ОС), `effective_cache_size=64MB` (только подсказка планировщику), `work_mem=2MB`, `maintenance_work_mem=16MB`, `wal_buffers=1MB`, `max_wal_size=64MB`, `jit=off` (LLVM-JIT ест память и не нужен коротким запросам), `huge_pages=off`, без параллельных воркеров (`max_worker_processes=2`, `max_parallel_*=0`), `autovacuum_max_workers=1`.
- **Бэкапы PostgreSQL:** `pg_dump` по расписанию, копии хранятся вне VPS — решить при этапе 4 перехода на монолит.
- **Оценка ресурсов.** Замер локального стенда в простое (`docker stats`, без `mem_limit`, хост 30 ГБ): app ~630 МБ, PostgreSQL ~40 МБ (с настройками выше; без них было ~75 МБ). Без лимита JVM не ужимает heap, с `mem_limit` потребление ниже; под нагрузкой heap растёт.
- Reverse proxy (Caddy или nginx), CI (GitHub Actions или GitLab CI), registry и хранение секретов — решить при реализации деплоя. Для SSE (`text/event-stream`) у proxy должны быть выключены буферизация и короткий read-timeout (app отдаёт `X-Accel-Buffering: no`, ping раз в 5 с); при выборе proxy проверить.

## 11. Отклонённые решения

### Сервисы и модули

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Микросервисы (5 JVM: два gateway, user-auth, game, admin-auth) + Redpanda + Debezium | Не помещаются в VPS 1 ГБ (~3 ГБ в простое), межсервисные вызовы, прокси и CDC ради одного разработчика | Модульный монолит (Spring Modulith): модули с явными границами, общение через `<m>-api` и события |
| Отдельный сервис на каждую игру | N баз, образов и деплоев при одинаковой инфраструктуре пошаговых игр | Один модуль `game` + модули `games/*` |
| Отдельный модуль game-lobby | Комнаты и партии тесно связаны (старт комнаты создаёт партию); пришлось бы делать вызовы и события между модулями | Комнаты внутри `game` |
| Логика игр в `libs/` | `libs/` — только инфраструктура без бизнес-логики | `games/` |
| Логика игр пакетами внутри модуля game | Границы между играми никто не контролирует, игру нельзя вынести в отдельный сервис | Модуль на игру в `games/` |
| Без общих `libs` | Дублирование инфраструктурного кода в каждом модуле | `libs/common-*` |

### Протоколы и взаимодействие

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| `@Schema(requiredMode = REQUIRED)` на каждом поле DTO | Легко забыть на новом поле, много шума | `KotlinRequiredPropertiesConverter` по nullability Kotlin |
| Ход без кода игры в пути (`/api/rooms/{id}/session/moves`, тело `{move: {...}}`) | Из запроса не видно, чей это ход; некуда повесить схему хода игры | `/api/games/{gameCode}/rooms/{roomId}/moves` + конверт `{type, data}` |
| WebSocket для Каркассона | Для пошаговой игры избыточно | REST + SSE; WS — только для real-time игр |
| Короткий поллинг событий по `afterSeq` (в том числе каждым потоком из журнала) | Задержка доставки до интервала опроса, нагрузка на БД растёт с числом зрителей | Postgres LISTEN/NOTIFY |
| Внутрипроцессная шина событий потоков (публикация в транзакции, номер по порядку коммитов, один поток раздачи) | Порядок коммитов пришлось бы воспроизводить самим (нумерация, пропуск откатов); NOTIFY даёт его даром | Postgres LISTEN/NOTIFY |
| Long polling | Ожидающим запросам всё равно нужна рассылка о новом ходе (или частый опрос БД): сложность как у SSE без его преимуществ | SSE |
| seq/version у событий комнаты | Нет случая, где нужно версионировать состояние комнаты | События с полным значением + маркер против гонки со снапшотом |
| Отдельные события на вход, выход, роль, ник, владельца | Лишняя сложность на клиенте | Одно `users-changed` с владельцем и полным списком |

### Стек

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Spring Boot 3.5 | Предыдущая линейка, срок поддержки короче | Spring Boot 4.x |
| WebFlux + корутины | Сложнее в разработке и отладке, нужен R2DBC | Spring MVC + virtual threads |
| JPA/Hibernate | Больше памяти (VPS 1 ГБ) и магии сессий/ленивых связей | Exposed DSL |
| Spring Data JDBC (`CrudRepository`, `@Query`, `JdbcClient`) | `@Query` и `JdbcClient` — сырой SQL, непереносимый между БД | Exposed DSL |

### Данные

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Строки таблиц (`ResultRow`) в сервисном слое | Сервисы зависят от формата хранения (строки вместо enum) | Модели + бин-маппер в репозитории |
| MapStruct для строки ↔ модель | kapt замедляет сборку, тонкости с Kotlin data-классами | Ручные `@Component`-мапперы |
| Двухслойный репозиторий только при нескольких хранилищах | Разнобой между сервисами, сервисы зависят от технологии хранения | Всегда фасад + `…ExposedRepository` |
| Сервисы возвращают DTO контрактов | Бизнес-логика зависит от формата API | Модели; DTO — в контроллере через `*ResponseMapper` |
| Ассемблер ответа, который сам ходит в репозитории | Чтение данных смешано с маппингом | Сервис собирает композитную модель, маппер только преобразует |
| `ApiException(HttpStatus, …)` из сервисов | Сервисный слой знает про HTTP | Иерархия классов ошибок в `libs/common-exception` |
| Плоская иерархия ошибок | Нельзя одной веткой отличить ошибки клиента от ошибок сервера | `ClientException` / `ServerException` |
| Класс на каждую бизнес-ошибку | Десятки мелких классов без пользы | Каталоги-фабрики `<Фича>Errors` |
| Отдельные таблицы на каждую игру | Каждая игра тащит свои миграции и репозитории в общий модуль | JSON-настройки + журнал событий |
| `bigint identity` для id | Предсказуемые id, неизвестны до вставки | UUID v7 |
| Имена таблиц во множественном числе | Решили единообразно в единственном | Единственное число |
| Встраиваемая SQLite вместо PostgreSQL | Один писатель на всю БД; конкурентность через sqlite-jdbc потребовала обхода драйвера и тонких настроек транзакций (`DEFERRED` + `explicit_readonly`) | PostgreSQL с настройками под малую память |
| Реплика профилей в game (таблица-копия по событию с `version`) | В одной БД копия не нужна: лишние таблица, событие с версией и идемпотентный upsert | Join с таблицей `user` через публичное описание в `user-auth-api` |
| Реестр публикаций событий Spring Modulith (`event_publication`) | Нужен для доставки данных между процессами и повторов; слушатели теперь только рассылают уведомления | `@TransactionalEventListener(AFTER_COMMIT)` |
| Сборка образов на VPS | Gradle съедает 2–3 ГБ RAM рабочего сервера | Сборка в CI + registry |

### Миграции

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Время или порядковый номер в имени миграции | Порядок и так задаёт master | `<дата>-<ДЕЙСТВИЕ>-<таблица>` |
| Папка на каждую миграцию | Выбрана другая раскладка | yaml в `DDL/` и `DML/` |
| SQL-миграции (`sqlFile`) | Диалектный SQL непереносим между БД | Абстрактные изменения Liquibase в YAML |
| Префикс модуля в имени таблицы и файла миграции (`"game#room"`) | В одной БД без схем хватает уникальности имён; `#` давал предупреждения Liquibase во вложенных jar | Имя таблицы уникально в приложении, файл — в папке модуля |
| `includeAll` | DML, зависящий от DDL, может выполниться не вовремя | Явный список в master |
| Фиксированный автор `qgames` | В `DATABASECHANGELOG` не видно, кто автор | `git config user.name` |
| Несколько таблиц в одной миграции | Сложнее откатывать и ревьюить | Одна таблица — одна миграция |

### Аутентификация

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| HMAC-подпись JWT | Секрет проверки совпадает с секретом подписи, модуль-проверяющий может выпускать токены; сложнее ротация | ES256, проверка публичным ключом |
| Google Authorization Code flow на бэкенде | Редиректы, хранение state/PKCE на сервере, сложнее | ID token с фронта |
| Строгая ротация refresh без окна терпимости | Параллельные вкладки, обновляющие сессию одной кукой, разлогинивают пользователя | Окно терпимости 10 с |
| BCrypt | Слабее против GPU-перебора, ограничение в 72 байта | Argon2id |
| Фронт и API на разных доменах | Пришлось бы ставить `SameSite=None` и вводить CSRF-токены, а сторонние куки блокируются браузерами | Поддомены одного домена |
| CORS с wildcard origin | С `allowCredentials` запрещён спецификацией, и любой сайт мог бы делать запросы с куками пользователя | Явный список `CORS_ALLOWED_ORIGINS` |
| Origin админки (3001) в CORS портала (`/api/**`) | Админке нужны только `/api/admin/**`; лишний origin расширяет круг сайтов с доступом к сессии игрока | Origin портала на `/api/**`, origin админки — только на `/api/admin/**` |
| `http://localhost:5173` в CORS по умолчанию | Порт Vite из первой настройки, портал живёт на 3000 | `http://localhost:3000` |
| Автопривязка Google по email (при verified email у нас и в Google) | Аккаунты создаёт только Google, подтверждать email у нас нечем | Только явная привязка; коллизия email — 409 `ACCOUNT_LINK_REQUIRED` |
| Уникальный ник | Не нужен: игроки различаются по id | Неуникальный ник |
| Google-аккаунту: ник из профиля Google (`given_name`) | Другие игроки видели бы настоящее имя | Сгенерированный `player-xxxxxx` |
| Google-аккаунту: запрос ника через ошибку `PROFILE_REQUIRED` и повтор входа | Лишний шаг при первом входе | Сгенерированный ник, смена в профиле |
| Отдельная ручка смены только ника | Аватар тоже меняется | Одна `PATCH /profile` на ник и аватар |

### Каталог игр

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| Флаг `disabled` у игры | Нужно ещё и скрыть игру из списка, оставив доступ по прямой ссылке | `publish_status` (см. §4 «Каталог игр») |
| Статусы `DISABLED` (временно запретить новые комнаты и партии, идущие продолжаются) и `FORCE_DISABLED` (отключить с удалением комнат) наряду с `NOT_PUBLISHED` | Решение пользователя: лишние режимы недоступности | Три статуса `PUBLISHED`/`HIDDEN`/`NOT_PUBLISHED`; отключение — перевод в `NOT_PUBLISHED`, комнаты удаляет `NotPublishedGameCleanupJob` |
| Поле `publishStatus` (публичный `GamePublishStatus`) в ответах каталога и игры, 409 `GAME_UNAVAILABLE` | Решение пользователя: без `DISABLED` наружу выходят только доступные игры, статус фронту не нужен | Поля нет; недоступная игра — 404 `GAME_NOT_FOUND` |
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
| Удаление комнаты владельцем | Хватает автоудаления пустых комнат (`EmptyRoomCleanupJob`), а ручное удаление выкидывает остальных участников и стирает историю партий | Только автоудаление пустых комнат |

### Настройки и таймеры

| Вариант | Почему отклонён | Что вместо |
|---|---|---|
| `gameType` в теле запроса (`POST /api/rooms {gameType}`) | Тип игры в каждой ручке передаётся по-разному | `gameCode` в пути `/api/games/{gameCode}/rooms/...` |
| Ключ настроек по имени игры (`nothing: {...}`) | Дублирует дискриминатор | Полиморфный `gameSettings` (`oneOf` по `gameCode`) |
| Плоские nullable-поля настроек / нетипизированный `settings` | Поля копятся с каждой игрой / нет типов на фронте | Полиморфный `gameSettings` + границы в `GET /api/games/{gameCode}` |
| Только `@Min/@Max` в DTO без ручки параметров игры | Границы одинаковы для всех игр, режимы фронт знает сам | `GET /api/games/{gameCode}` |
| Таймауты лениво при GET комнаты | GET меняет данные, нечего публиковать в SSE | `MoveTimeoutJob` раз в секунду |
| Джоб блокирует сначала партию, потом комнату | Обратный порядок к ходу — дедлоки | Сначала блокировка комнаты |
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
| init-скрипты только с очисткой, без фикстур | Тесты создавали бы данные через API, медленнее и шумнее | `init/<модуль>.sql` с фикстурами + `TestData` |
| Программная очистка БД из кода теста | Состояние не видно из ресурсов | `init/<модуль>.sql` через `@Sql` |
| Создание ролей и БД отдельным init-скриптом (`postgres-init`) | Одна роль приложения — её создаёт сам образ из `POSTGRES_USER`/`POSTGRES_DB` | Переменные образа postgres |
| `jsonb` для JSON-колонок | Решение пользователя: поля JSON не запрашиваем, а `jsonb` переставляет ключи | `json` |
| `timestamp` без пояса + UTC в JVM | Корректность держалась бы на поясе JVM | `timestamptz` |
