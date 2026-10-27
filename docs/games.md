# Модули игр (`games/`): правила кода и чек-лист новой игры

Справочник к CLAUDE.md §2: читать, когда трогаешь `games/*`, движок партий или добавляешь игру. Устройство модели игры (патчи, `GameRules`, блоки core, коды событий) — `../qgames-workspace/adr/c4/3-backend-games.md`, обоснования — `adr/game/session-events-format.md`, `adr/backend/game-rules-modules.md`.

## Правила кода

- Модуль игры — **чистый Kotlin**: без Spring, БД, сети и прочего IO. Зависит только от `games/game-core`.
- События порождаются только построителями `SessionEvents.put/patch/delete/info`, применяет их только общий `SessionPatch`. Своих редьюсеров у игр нет. Одно событие — одна операция; действие, меняющее несколько мест, порождает несколько событий.
- У элементов адресуемых массивов обязателен `id`, в том числе у замаскированных.
- `mask` маскирует только `value`. Инвариант «свёртка маскированных событий = маска полной свёртки» обязателен к тесту для каждой игры.
- `result-set` ставится только один раз и только в том же наборе событий, что `session-finished` (это проверяет `SessionEngine`).
- Поля игры у игрока (`playerClass`) лежат прямо в объекте игрока. Совпадение с полями core (`id`, `moveOptions`) — ошибка. Начальные значения в `GameStart.players` обязательны для каждого игрока.
- Случайность (порядок тайлов, кубики) берётся только из seed партии.
- Состояние и ход — простые data-классы без вычисляемых свойств.
- Формат состояния, хода, настроек и событий меняется свободно, совместимость со старыми журналами не учитывается (`adr/system/game-data-compatibility.md`).
- Пустые модули игр не заводятся: `games/carcassonne` появится вместе с правилами.

## Чек-лист новой игры

Новый модуль `games/<игра>`, плюс:

- зависимость в `game/game/build.gradle.kts`, бин правил в `GamesConfig`;
- DTO состояния (`<Игра>SessionStateDto` с `result`; если у игрока есть поля игры — свой DTO игрока) — новый вариант `oneOf` `SessionStateDto` в `game-api` и ветка в `SessionEventResponseMapper.stateClass`. Новые коды событий дописываются в `SessionEventCodes.DESCRIPTION`;
- если игра задаёт `moveRules` — `<Игра>MoveRulesDto` как вариант `oneOf` `SessionMoveRulesDto` и ветка в `SessionEventResponseMapper.moveRulesClass`;
- если у хода есть параметры — `<Игра>MoveDataDto` в `MoveDataSchemas.CLASSES`;
- значение в enum `GameCode` модели game и контракта `game-api`, константа в `GameCodes`, код в `allowableValues` параметра `{gameCode}` (`GameApi`, `GameRoomApi`); `GameRules.code` совпадает с кодом;
- строка в таблице `game` DML-миграцией `<дата>-INSERT-game`, та же строка — в `init/game.sql` и `TestData`. Пока игра в разработке, статус — `NOT_PUBLISHED`, и модуль правил с DTO ещё не обязательны;
- `<Игра>SettingsDto` и `<Игра>SettingsLimitsDto` как варианты `oneOf` `GameSettingsDto`/`GameSettingsLimitsDto` (`@JsonSubTypes`, `discriminatorMapping`), ветки в `RoomSettingsResponseMapper`;
- новые варианты union и enum — в `PortalOpenApiContractScenariosTest`;
- игра в `adr/c4/3-backend-games.md` (раздел «Игры»).

Новый модуль приложения и DDL-миграции не нужны.
