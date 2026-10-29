package site.qgames.games.core

import java.util.UUID

/**
 * Правила одной игры: описание, параметры и чистые функции. Одинаковые входы дают одинаковый результат, в том числе
 * при переигрывании журнала. Случайность берётся только из seed.
 *
 * Партия — журнал событий ([SessionEvent]): каждое событие — одна операция над документом партии `{status, meta, state}`
 * (PUT, PATCH, DELETE или INFO по пути). Состояние не хранится, а собирается свёрткой журнала одним общим применителем
 * (DocumentPatch); своих редьюсеров у игры нет. Каждая игра — надстройка над core: платформа ([SessionEngine]) ведёт
 * общие блоки состояния — игроков и их варианты ходов ([Players]), очерёдность и раунды ([Turns]), часы с паузой
 * ([Timer]) и итог ([Result]) — и сама добавляет их события; механику, которая игре не нужна (например, часы), игра
 * просто не задействует. Игра решает, какие события происходят в ответ на ход ([decide]) и на истечение времени
 * ([onTimeout]), и задаёт их коды.
 *
 * Поля игровой части состояния [G] лежат в документе в `state` рядом с блоками core (`players`, `timer`, `turns`,
 * `result`) и не совпадают с ними по именам; события игры адресуют их путями от `state` (например,
 * `PATCH ["state"] {moves}`).
 * [G], ход [M] и настройки [S], [A] — простые data-классы без вычисляемых свойств: сервис game пишет их в журнал как JSON.
 *
 * Настройки двух уровней. Корневые — advanced [A]: полный набор правил, по которому идёт партия (часы — поле
 * [TimeControlSettings], см. [clock]); партия знает только их. Simple [S] — короткий выбор для игроков (вариант, пресет
 * времени), из него игра выводит advanced ([toAdvanced]). Какие правила владелец комнаты задаёт в advanced, решает игра;
 * остальное выводится из advanced ([advancedRules]).
 *
 * Игроки партии закреплены до её конца: игрок, закрывший поток комнаты, остаётся игроком, а партия о его отсутствии
 * не знает (время идёт, ходы по таймауту — как обычно). Хука на отсутствие игрока пока нет.
 */
interface GameRules<G : Any, M : Any, S : Any, A : Any> {

    /**
     * Код игры, например `nothing`: совпадает с кодом в каталоге игр сервиса game (таблица game, enum GameCode)
     * и в путях API (`/api/games/{gameCode}`).
     */
    val code: String
    val minPlayers: Int
    val maxPlayers: Int

    /** Класс игровой части состояния ([SessionState.game]). */
    val gameStateClass: Class<G>
    val moveClass: Class<M>
    val simpleClass: Class<S>
    val advancedClass: Class<A>

    /** Можно ли играть без таймера и границы часов advanced-настроек. */
    val timeControl: TimeControlRules

    /** Simple-настройки по умолчанию: с ними создаётся комната. */
    val defaultSimple: S

    /** Границы настроек игры для формы. Формат задаёт игра, сервис game переводит его в DTO контракта. */
    val settingsLimits: Any

    /**
     * Класс полей игры у игрока ([site.qgames.games.core.PlayerState.game]): в документе они лежат прямо в объекте
     * игрока `state.players[]` рядом с `id` и `moveOptions` и не называются так же. null — своих полей у игрока нет.
     */
    val playerClass: Class<*>? get() = null

    /** Нарушения в simple-настройках; пустой список — подходят. Поле — путь внутри [S], например `variant`. */
    fun validateSimple(settings: S): List<SettingsViolation>

    /**
     * Нарушения в advanced-настройках, кроме часов (их проверяет платформа по [timeControl]). Поле — путь внутри [A],
     * например `rounds`.
     */
    fun validateAdvanced(settings: A): List<SettingsViolation>

    /** Advanced-настройки, выведенные из проверенных simple-настроек: с ними идёт партия в simple-режиме комнаты. */
    fun toAdvanced(settings: S): A

    /** Часы advanced-настроек (их поле `timeControl`). */
    fun clock(settings: A): TimeControlSettings

    /**
     * Итоговые правила партии для клиента (`meta.advancedRules`): выводятся из advanced-настроек, по ним клиент
     * подсказывает ввод хода, не зная правил игры. Записываются в `session-started` и не меняются. null — подсказок нет.
     */
    fun advancedRules(settings: A): Any? = null

    /**
     * Начало партии: игровая часть состояния и очерёдность. Игроки перечислены в порядке мест, настройки уже
     * проверены [validateAdvanced]. Из этого платформа собирает первое событие журнала `session-started`.
     */
    fun start(players: List<UUID>, seed: Long, settings: A): GameStart<G>

    /**
     * Ход игрока [playerId] в полном состоянии [state]: события игры (и core, которыми игра управляет сама, —
     * например `turn-changed`) или отказ. Конец партии — событие `session-finished` в списке
     * ([SessionEvents.finished]); итог партии (`result-set`, [Result]) — только в том же наборе событий. Коды событий
     * игры не совпадают с кодами платформы и core. [settings] — advanced-настройки, закреплённые за партией при старте.
     */
    fun decide(state: SessionState<G>, settings: A, playerId: UUID, move: M): Decision

    /** Время хода текущего игрока истекло. Пустой список — ничего не происходит (ход ничей). */
    fun onTimeout(state: SessionState<G>): List<SessionEvent>

    /** Что сейчас может сделать игрок: варианты ходов в том же конверте, что тело хода. */
    fun moveOptions(state: SessionState<G>, playerId: UUID): List<MoveOption>

    /**
     * Собственное событие игры для проекции [viewer]: маскируется только значение (`value`), код, метод и путь
     * остаются, событие доставляется. По умолчанию событие публичное.
     */
    fun mask(event: SessionEvent, viewer: ViewerKey): SessionEvent = event

    /** Игровая часть начального состояния (событие `session-started`) для проекции [viewer]. По умолчанию публичная. */
    fun maskGame(game: G, viewer: ViewerKey): G = game
}

/** Начало партии: игровая часть состояния, очерёдность ([Turns]) и поля игры у игроков. */
data class GameStart<G : Any>(
    val game: G,
    val turns: TurnsState,
    /** Поля игры у каждого игрока (объекты [GameRules.playerClass]); пусто — у игры их нет. */
    val players: Map<UUID, Any> = emptyMap(),
)

/** Результат хода по правилам игры. */
sealed interface Decision {

    data class Accepted(val events: List<SessionEvent>) : Decision

    /** [code] — машиночитаемая причина, отдаётся клиенту. */
    data class Rejected(val code: String, val message: String) : Decision
}

/** Нарушение в настройках игры: [field] — путь к полю внутри настроек игры, [message] — описание для клиента. */
data class SettingsViolation(val field: String, val message: String)
