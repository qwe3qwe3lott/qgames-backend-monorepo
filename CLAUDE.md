# CLAUDE.md — правила разработки qgames-backend

Бэкенд игрового портала qgames: модульный монолит (Spring Modulith) на Kotlin + Spring Boot в одном монорепозитории. Фронтенд — отдельный репозиторий `qgames-frontend-monorepo`, воркспейс агентов — `qgames-workspace`. Все три репозитория лежат рядом в одной родительской папке, поэтому фронтенд доступен по относительному пути `../qgames-frontend-monorepo`.

**Архитектура системы — `../qgames-workspace/adr/`** (дальше `adr/`): ADR (почему так), диаграммы C4 (из чего состоит и как связано), ERD (схема БД). Перед анализом задачи прочитать `adr/README.md` и нужные диаграммы. Этот файл — правила написания кода.

## 0. Процесс принятия решений (главное правило)

- **Архитектурные решения не принимаются без пользователя.** Новый модуль приложения, технология, протокол, схема хранения, формат контракта, изменение границ модулей, способ деплоя и т.п. — сначала вопрос пользователю с вариантами и рекомендацией, затем реализация.
- Если правило здесь противоречит задаче — спросить, а не обходить молча.
- **Не замалчивать проблемы.** Если по ходу работы что-то смущает (риск безопасности, противоречие правилам или ранее сказанному, сомнительное поведение, техдолг, расхождение кода и документации), сразу сказать пользователю и спросить, как поступить, предложив варианты. Не чинить молча и не откладывать «на потом» без согласования.
- **Git:** рабочая и основная ветка — `master` (`main` не используется). Коммиты делает пользователь; без явной просьбы не коммитить и не пушить.

### Как вести этот файл

- **Здесь — «как писать код»:** разделы 1–10 — действующие правила. Правило, нужное почти в любой задаче, записывается в профильный раздел, правило одной области — во вложенный `CLAUDE.md` её папки (сейчас `games/CLAUDE.md`): Claude Code подгружает его при работе с файлами папки, корневой файл ссылается на него для смежных мест. Папки `docs/` нет.
- **«Почему» и устройство системы — в `adr/`, а не здесь.** Обоснование решения, отвергнутые варианты и последствия — в ADR (`adr/<группа>/<slug>.md`, ссылка из профильного раздела). Устройство (компоненты, потоки, схема БД) — в C4 и ERD. Изменение кода, затрагивающее диаграмму, таблицу или решение, обновляет `adr/` в той же задаче (правила ведения — `adr/README.md`).
- **Отвергнутые варианты** лежат в разделе «Рассмотренные варианты» своего ADR. Повторно такой вариант предлагать только с новым аргументом, и этот аргумент нужно назвать явно.
- **Журнала изменений и дат нет:** файлы описывают текущее состояние. Если решение меняется, правило переписывается на месте, ADR — тоже, а прежний вариант уходит в его «Рассмотренные варианты».
- Отложенные вопросы записываются пометкой «решить при …» в профильном разделе или в «Открытых вопросах» ADR.
- **От технологии, приложения или фичи отказались — сведения о ней удаляются целиком**, включая её ADR.

## 1. Модули приложения

Бэкенд — **модульный монолит** (Spring Modulith): одно приложение `app` (один процесс, порт **8080**) из модулей с явными границами. Модули общаются только через публичный контракт друг друга (`<m>-api`) и события (`adr/backend/backend-architecture.md`, схема — `adr/c4/3-backend.md`).

| Модуль | Назначение | Пути API |
|---|---|---|
| `user-auth` | Аккаунты игроков (неизменяемый логин, ник), регистрация через Google, способы входа (Google, логин и пароль), выпуск JWT игроков, куки сессии портала | `/api/auth/*`, `/api/users/me/*` |
| `game` | Комнаты и партии **всех** игр; ник и аватар игроков читает из таблицы `user` модуля user-auth (только чтение, через `user-auth-api`). Правила конкретных игр подключаются модулями `games/*` | `/api/games/*`, `/api/rooms/*` |
| `admin-auth` | Аккаунты администраторов (логин и пароль, без регистрации), выпуск JWT администраторов, куки сессии админки | `/api/admin/auth/*`, `/api/admin/admins/*` |
| `app` | Приложение: `@SpringBootApplication`, Security (две цепочки), группы OpenAPI, `application.yml`, сводный Liquibase-master, Docker-образ | — |

Отдельный модуль на каждую игру **не заводим**. Новая игра — новый модуль в `games/` (см. `games/CLAUDE.md`).

**Портал и админка — два фронта одного приложения:**
- портал — `/api/**` (кроме `/api/admin/**`), OpenAPI-группа `portal` (`/v3/api-docs/portal`); админка — `/api/admin/**`, группа `admin` (`/v3/api-docs/admin`). Swagger UI один, с выбором группы. Фронт генерирует по каждой группе свой клиент Orval;
- портал — `http://localhost:3000`, админка (CSR SPA) — `http://localhost:3001`; в проде портал — `https://<домен>`, админка — поддомен `https://admin.<домен>` (same-site). **Каждый фронт ходит в API своего origin:** reverse proxy (nginx, образ `web` фронтенда) отдаёт статику и проксирует в app `/api/**` с хоста портала и только `/api/admin/**` с хоста админки, локально в `pnpm dev` — dev-proxy rsbuild. CORS нет (`adr/security/session-tokens.md`);
- соглашения у обеих групп одинаковые (§3): REST/JSON, ошибки — ProblemDetail с `code`, Swagger на русском;
- пути аутентификации (`…/auth/login`, `…/auth/refresh`, `…/auth/logout`) и 401 `UNAUTHENTICATED` устроены одинаково, отличается только префикс (`/api/auth`, `/api/admin/auth`): на этом держится общий fetch-mutator фронта;
- текущий админ — `GET /api/admin/admins/me` (по нему фронт определяет, вошёл ли админ), список админов — `GET /api/admin/admins`;
- ролей нет, все админы равны;
- admin-auth — упрощённая копия user-auth (без регистрации, Google и событий): имена классов и раскладка те же.

## 2. Структура репозитория и модулей

```
buildSrc/                      convention-плагины: qgames.kotlin-library, qgames.spring-module, qgames.spring-service
gradle/libs.versions.toml      версии; всё, чем управляет Spring Boot BOM, без версий (Spring Modulith и Exposed — своими BOM)
libs/                          общие ИНФРАСТРУКТУРНЫЕ модули (без бизнес-логики)
  common-exception             иерархия ошибок (QgamesException → ClientException/ServerException → …) и их обработчики, подключаются автоматически
  common-web                   RequestUserContext, OpenAPI-кастомизация (тянет common-exception через api)
  common-test                  Testcontainers (singleton PostgreSQL), WireMock
games/                         ПРАВИЛА ИГР (бизнес-логика, но без инфраструктуры)
  game-core                    core игр: модель игры (GameRules, SessionEngine) и общие блоки — игроки, очередь, часы, итог
  nothing-game                 «игра в ничто» — тестовая игра для проверки движка
  checkers-game                шашки (вариант правил — настройка комнаты: русские, международные, английские)
  <игра>                       правила конкретной игры (например, games/carcassonne)
user-auth/, admin-auth/, game/ МОДУЛИ ПРИЛОЖЕНИЯ: <m>/<m> (реализация) и <m>/<m>-api (публичный контракт)
app/                           приложение: собирает модули; Security, OpenAPI, конфигурация, миграции, все интеграционные тесты
docker/                        Dockerfile приложения, dev-ключи
```

### Модули игр (`games/`)

Модуль игры — **чистый Kotlin**: без Spring, БД, сети и прочего IO, зависит только от `games/game-core`. Правила кода модулей игр и чек-лист новой игры — во вложенном **`games/CLAUDE.md`** (подгружается при работе в `games/`), модель игры (журнал событий-патчей, `GameRules`, блоки core, коды событий) — в **`adr/c4/3-backend-games.md`**: читать, когда трогаешь `games/*`, движок партий или добавляешь игру.

### Модули приложения: `<m>` и `<m>-api`

| Gradle-модуль | Содержимое |
|---|---|
| `<m>/<m>` | реализация модуля: контроллеры, сервисы, репозитории, джобы, слушатели, changelog'и миграций. Библиотека (convention `qgames.spring-module`) без `@SpringBootApplication` и `bootJar` |
| `<m>/<m>-api` | публичный контракт модуля: REST-интерфейсы контроллеров (`@HttpExchange` со Swagger-аннотациями), DTO, события, константы и интерфейсы, которые модуль даёт другим модулям и app |
| `app` | приложение (convention `qgames.spring-service`): `QgamesApplication` (пакет `site.qgames`), Security, OpenAPI-группы, `application.yml`, сводный Liquibase-master, `bootJar`, все интеграционные тесты |

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
  - `PersistenceExceptionHandler` — `SQLException` (Exposed бросает `ExposedSQLException`): нарушение уникальности (SQLSTATE `23505` в цепочке причин, `UniqueViolations`) → 409 `CONFLICT`, остальное — лог ERROR и 500 `INTERNAL_ERROR`.
  - Если клиенту важна причина гонки за уникальный индекс, сервис переводит нарушение в доменный код через `UniqueViolations.isUniqueViolationOf` (по имени индекса), например `LoginService.registerWithGoogle`: `user_login_uq` → `LOGIN_TAKEN`. `ExposedSQLException` — checked `SQLException`, поэтому ловится `Exception`. Общий `CONFLICT` — запасной вариант, когда смысл не важен.
- Неизвестный или отсутствующий дискриминатор полиморфного DTO (`InvalidTypeIdException` Jackson) — 400 `VALIDATION_ERROR` с ошибкой по полю `errors[<путь>.<дискриминатор>]`, например `settings.gameSettings.gameCode`.
- Advice библиотеки имеют самый низкий приоритет. **Собственный advice модуля** объявляет `@Order(Ordered.HIGHEST_PRECEDENCE)`, иначе его исключения может перехватить ловушка `Exception`.
- `@Transactional(noRollbackFor = [QgamesException::class])` — когда изменение (счётчик попыток, отзыв сессии) должно сохраниться, несмотря на ошибку.

## 3. Взаимодействие

- **Фронт ↔ приложение:** REST/JSON в app (порт 8080) через reverse proxy того же origin, что и страница (§1). Ошибки — `ProblemDetail` с машиночитаемым полем `code`. Контракт — интерфейсы и DTO `<m>-api` модулей; публикуется двумя группами OpenAPI: `portal` (`/api/**` кроме `/api/admin/**`) и `admin` (`/api/admin/**`). Валидация тел — `@Valid` в контроллерах модулей.
  - Токены сессии в контракт не входят: auth-контроллеры модулей кладут их в HttpOnly-куки (`adr/c4/3-backend-auth.md`) и отдают в теле только пользователя (`UserResponse`, `AdminResponse`). DTO с токенами (`AuthResponse`, `TokenPair`, `RefreshTokenRequest`) не заводятся — это проверяют `PortalOpenApiContractScenariosTest` и `AdminOpenApiContractScenariosTest`.
  - Одноимённые DTO разных групп (например, `LoginRequest` user-auth и admin-auth) допустимы: у каждой группы свои components. Внутри одной группы простые имена классов DTO уникальны: swagger-core называет схему по простому имени класса.
- **Документация API (Swagger):** у каждой ручки в `<m>-api` интерфейсе обязательно есть:
  - `@Operation(summary, description)` на русском: что делает метод, зачем он нужен и какие у него особенности;
  - `@ApiResponses` со всеми реально возможными статусами (200, 400, 401, …). Описания на русском, у ошибок в описании указаны значения `code` (например, «Неверный логин или пароль (INVALID_CREDENTIALS)»);
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
- **Real-time в играх:** REST (ходы и действия) + **SSE** (события), рассылка потокам — Postgres `LISTEN/NOTIFY`. WebSocket — только для будущих игр с real-time перемещением игроков. Устройство потоков комнаты и партии, кадры, гонки со снапшотом, OpenAPI событий — в **`adr/c4/dynamic/room-stream.md`** и **`adr/c4/dynamic/move.md`**: читать, когда трогаешь SSE-потоки или рассылку событий (решение — `adr/game/realtime-transport.md`).

## 4. Данные

- **PostgreSQL 18, одна БД `qgames` и одна роль `qgames` на приложение** — их создаёт сам образ из `POSTGRES_USER`/`POSTGRES_DB` на пустом томе (отдельного init-скрипта нет). Модуль пишет только в свои таблицы.
- **Доступ к данным — Exposed DSL** (последний стабильный 1.x, без DAO) в транзакциях Spring: `@Transactional` остаётся, менеджер транзакций — `SpringTransactionManager` Exposed (`exposed-spring-boot4-starter` в app). JPA/Hibernate и Spring Data JDBC не используются.
- **Без сырого SQL:** доступ к БД — только через Exposed DSL, миграции — только абстрактные изменения Liquibase, диалектных функций нет. **Единственное исключение — `pg_notify` (`PgNotifier`) и `LISTEN` (`RoomEventListener`)**: у Exposed нет DSL для LISTEN/NOTIFY. SQL-фикстуры тестов — тестовые данные, не доступ из кода. Поэтому без `RETURNING` (выборка и изменение одних строк — в одной транзакции), без CHECK-ограничений и частичных индексов (их нет среди абстрактных изменений Liquibase open source: значения проверяет код через enum).
- **Пул Hikari — 5 соединений** (`minimum-idle` 2) плюс одно соединение `RoomEventListener` вне пула: один инстанс, запросы короткие, а каждое соединение — отдельный процесс PostgreSQL.
- **Блокировки:** `forUpdate()` Exposed там, где нужна блокировка строки (`lock(room)` — `SELECT … FOR UPDATE`). `SKIP LOCKED` не используется (один инстанс).
- **Именование:** таблицы в единственном числе, snake_case, без префиксов модулей; **имя таблицы уникально в приложении**, при совпадении — уточнение по смыслу (`user_refresh_token`, `admin_refresh_token`). Индексы и ограничения — с именем таблицы, имена уникальны в БД: `user_refresh_token_hash_uq`, `room_empty_since_idx`, `room_member_pk`, `room_session_room_fk`. `user` — зарезервированное слово PostgreSQL: Exposed и Liquibase экранируют его сами, в SQL-фикстурах — `"user"`.
- **Чужие таблицы — только чтение и только через описания из `<m>-api`:** модуль, которому нужны данные другого модуля, соединяет свои таблицы с его таблицей (join) через публичное описание Exposed в `<m>-api` (`api.table.<фича>`, только нужные колонки). Писать в чужие таблицы нельзя; полное описание таблицы — в реализации модуля-владельца, публичное — подмножество его колонок. Сейчас: `UserProfileTable` (`id`, `nickname`, `avatar` таблицы `user`) в `user-auth-api`, его читает game.
- **ID:** UUID v7, генерирует приложение (`Uuid.generateV7()` Kotlin, `util/Ids.kt` модуля), не БД. Тип — родной `uuid` (`javaUUID` в Exposed, `type: uuid` в миграциях). Строки каталога игр (DML) — с фиксированными UUID. У таблиц-связей и журналов, на которые никто не ссылается, `id` нет: PK составной естественный (`room_member`, `room_session_player`, `room_session_event`), см. `adr/backend/data-access.md`.
- **Время:** колонки `timestamptz` (`type: timestamp with time zone` в миграциях), в Exposed — `timestamp()`, в коде `Instant` из бина `Clock`. pgjdbc передаёт значение со смещением JVM, поэтому момент сохраняется верно при любом часовом поясе JVM; пояс не меняем. Точность — микросекунды.
- **JSON** (настройки игры, данные событий журнала) — `json<String>` Exposed: строка JSON как есть (Jackson в мапперах), тип `json` (не `jsonb`: текст хранится как записан, порядок ключей сохраняется).

### События модулей

- **Источник** (сейчас user-auth): событие — Kotlin data class в `<m>-api` (`api.event.<фича>`), публикуется `ApplicationEventPublisher` (`UserProfilePublisher`, `@Transactional(MANDATORY)`) в транзакции бизнес-изменения. Сейчас — `UserProfileChanged(id, nickname, avatar)` при создании аккаунта и каждой смене профиля; сохранение без изменений события не публикует. Правки профиля — «последняя запись побеждает», версии строки нет.
- **Потребитель** (сейчас game): `@TransactionalEventListener(AFTER_COMMIT)` на тип события из `<m>-api` источника, синхронно после коммита источника; читает БД в своей транзакции (`REQUIRES_NEW`). Данные источника потребитель не копирует: сами данные он читает из таблицы источника через описание из `<m>-api`. `UserProfileListener` game рассылает `users-changed` во все комнаты пользователя.

### Каталог игр, комнаты и партии (модуль `game`)

Статусы публикации игр, вход и выход из комнат, присутствие, владение, автоудаление, конкурентность, журнал партии, ручка хода, таймер и пауза — в **`adr/c4/3-backend-game.md`** (таблицы — `adr/erd/game.md`): читать, когда трогаешь модуль `game`. Главное: каждая мутация комнаты, участников и партии — под блокировкой комнаты (`RoomRepository.lockById`); состояние партии не хранится, это свёртка журнала событий.

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
- **Новая или изменённая CREATE-миграция обновляет ERD модуля** `adr/erd/<модуль>.md` (таблицы, колонки, ограничения, связи) в той же задаче.
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

- Две цепочки Security в app: админка — `/api/admin/**` (токены admin-auth), портал — всё остальное (токены user-auth). JWT — ES256, проверка локально по публичному ключу; без токена — 401 `UNAUTHENTICATED`.
- Токены — только в HttpOnly-куках (`HttpOnly; Secure; SameSite=Lax`), в теле ответов их нет. Same-origin через reverse proxy, CORS нет; CSRF-токенов нет, тела запросов — только JSON.
- Подробности (refresh и окно терпимости, сессия админа, первый админ, куки, пароли, Google, профиль и способы входа, ключи) — в **`adr/c4/3-backend-auth.md`**, модель CSRF и принятые риски — в `adr/security/session-tokens.md`: читать, когда трогаешь `user-auth`, `admin-auth`, Security, куки или JWT.

## 6. Один инстанс

Одно приложение, один инстанс. Несколько инстансов app не поддерживаются: в памяти процесса живут реестры SSE-потоков и кеш состояний партий, ShedLock не нужен.

- Состояние в памяти процесса допустимо только такое:
  - реестры открытых SSE-потоков (`RoomStreamRegistry`, `SessionStreamRegistry`): привязаны к соединениям, а присутствие хранится в БД (`room_connection` с арендой);
  - кеш состояний партий `SessionStateCache` (Caffeine): только ускоряет сборку, источник правды — журнал, состояние на seq неизменно.
- Счётчики, лимиты, блокировки — в БД (атомарные `UPDATE … WHERE …`). Гонки на уникальных ключах → 409: доменный код, если причина важна клиенту, иначе `CONFLICT` (`PersistenceExceptionHandler`), см. §2.
- **Присутствие — аренды в БД** (`room_connection`): приложение продлевает аренду живых потоков, а подключения, которые никто не продлевает (процесс упал или перезапущен, отключение не удалось), удаляет по истечении аренды `RoomConnectionCleanupJob`.
- Шедулеры (`@EnableScheduling` на `QgamesApplication`): джобы выбирают id без блокировки, затем каждую комнату или партию обрабатывают в своей транзакции под блокировкой комнаты с перепроверкой. У каждого джоба свой флаг включения `…-enabled`. В тестах джобы выключены и вызываются напрямую через `run()`.

## 7. Тесты

- Тесты **максимально интеграционные**: запросы через MockMvc в настоящие контроллеры на полном контексте приложения (все модули, настоящие цепочки Security и куки), реальная PostgreSQL (Testcontainers), внешние HTTP-системы (JWKS Google) — WireMock. Тривиальных тестов не пишем.
- **Все интеграционные тесты живут в `app/src/test/kotlin`** в пакетах модулей (`site.qgames.game.controller.room…`, `site.qgames.userauth…`); тесты Security, кук и OpenAPI — в `site.qgames.app…`; границы модулей — `ModularityTest`. Чистые тесты правил игр — в своих модулях `games/*`.
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
- **Профиль `local`** — локальный стенд: `app/src/main/resources/application-local.yml` накладывается поверх `application.yml` и содержит только переопределения и добавления (основной конфиг не дублируется). docker-compose включает его через `SPRING_PROFILES_ACTIVE=local`. Значения только для стенда (первый админ, тестовые игроки, `qgames.game.session.seed-header-enabled` — seed партии из заголовка `X-Qgames-Seed` для e2e-сценариев фронта) живут в нём, а не в env compose. Для прода будет профиль `prod` (`application-prod.yml`, `SPRING_PROFILES_ACTIVE=prod`) — решить при подготовке деплоя.
- Вход на стенде: админка — `admin` / `admin`; портал — тестовые игроки с логинами `qwe3qwe1` … `qwe3qwe10`, пароль `qwe3qwe3`.
- Google-вход: заполнить `GOOGLE_CLIENT_IDS` в `.env` (см. `.env.example`).
- Запуск из IDE: `docker compose up -d postgres`, затем `QgamesApplication` (или `./gradlew :app:bootRun`) с рабочим каталогом `app/` и профилем `local` (без него нет админа и тестовых игроков): значения по умолчанию в `application.yml` смотрят на localhost и `../docker/dev-keys`.
- Переменные окружения app: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `JWT_KEY_ID`/`JWT_PRIVATE_KEY_LOCATION`/`JWT_PUBLIC_KEY_LOCATION` (игроки), `ADMIN_JWT_KEY_ID`/`ADMIN_JWT_PRIVATE_KEY_LOCATION`/`ADMIN_JWT_PUBLIC_KEY_LOCATION` (админы), `GOOGLE_CLIENT_IDS`, `ADMIN_BOOTSTRAP_LOGIN`/`ADMIN_BOOTSTRAP_PASSWORD`, `COOKIES_SECURE`, `API_DOCS_ENABLED` (OpenAPI и Swagger UI, по умолчанию `true`; при `false` их ручки отвечают 404).
- Фронты на стенде: образ `web` фронтенда (nginx) — портал на 3000, админка на 3001, `/api` проксируется в app на 8080.
- Образ: multi-stage `docker/app.Dockerfile` (`:app:bootJar`). Контекст сборки — корень репозитория.

## 10. Развёртывание (один VPS)

Один VPS 1 ГБ, docker compose: nginx (образ `web` фронтенда) + app + PostgreSQL. Бюджет памяти, SSE через proxy, Swagger в проде — в **`adr/c4/deployment.md`**, флаги JVM и настройки PostgreSQL с обоснованием — в **`adr/system/memory-budget.md`**: читать, когда трогаешь Docker-образ, `docker-compose.yml`, память или деплой.

## 11. Архитектурные решения и отвергнутые варианты

В **`adr/`** (индекс — `adr/README.md`): читать нужные ADR перед тем, как предлагать архитектурную альтернативу или менять принятое решение.
