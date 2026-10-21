package site.qgames.games.api

import site.qgames.games.api.core.MoveOption
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.session.ViewerKey
import site.qgames.games.api.turns.TurnsState
import java.time.Duration
import java.util.UUID

/**
 * Правила одной игры: описание, параметры и чистые функции. Одинаковые входы дают одинаковый результат на любом
 * инстансе и при переигрывании журнала. Случайность берётся только из seed.
 *
 * Партия — журнал событий ([SessionEvent]); состояние не хранится, а собирается свёрткой журнала. Платформа
 * ([site.qgames.games.api.session.SessionEngine]) подключает к игре модули core (игроки и их варианты ходов), turns
 * (очерёдность и раунды) и timer (таймер хода, в том числе пауза) и сама добавляет их события. Игра решает, что
 * происходит в ответ на ход ([decide]) и на истечение времени ([onTimeout]), и сворачивает свои события ([evolve]).
 *
 * Игровая часть состояния [G], ход [M], настройки [C] и данные событий из [eventClasses] — простые data-классы без
 * вычисляемых свойств: сервис game пишет события в журнал как JSON. При несовместимом изменении формата событий
 * увеличивается [stateSchemaVersion].
 */
interface GameRules<G : Any, M : Any, C : Any> {

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
    val settingsClass: Class<C>
    val stateSchemaVersion: Int

    /** Собственные события игры: тип → класс данных. Типы не пересекаются с типами платформы и модулей. */
    val eventClasses: Map<String, Class<*>>

    /** Какие режимы времени поддерживает игра, их границы и режим по умолчанию. */
    val timeControl: TimeControlRules

    /**
     * Сколько игрок идущей партии может отсутствовать после выхода из комнаты, прежде чем перестанет считаться
     * присутствующим (место в партии при этом сохраняется). На паузе таймера отсчёт заморожен.
     */
    val reconnectGrace: Duration

    /** Настройки игры по умолчанию: ими заполняется форма создания комнаты. */
    val defaultSettings: C

    /** Границы настроек игры для формы. Формат задаёт игра, сервис game переводит его в DTO контракта. */
    val settingsLimits: Any

    /** Нарушения в настройках игры; пустой список — настройки подходят. Поле — путь внутри [C], например `rounds`. */
    fun validateSettings(settings: C): List<SettingsViolation>

    /**
     * Начало партии: игровая часть состояния и очерёдность. Игроки перечислены в порядке мест, настройки уже
     * проверены [validateSettings]. Из этого платформа собирает первое событие журнала `started`.
     */
    fun start(players: List<UUID>, seed: Long, settings: C): GameStart<G>

    /**
     * Ход игрока [playerId] в полном состоянии [state]: события игры (и модулей, которыми игра управляет сама, —
     * например `turns-changed`) или отказ. Конец партии — событие `session-finished` в списке
     * ([site.qgames.games.api.session.SessionEvents.finished]).
     */
    fun decide(state: SessionState<G>, playerId: UUID, move: M): Decision

    /** Время хода текущего игрока истекло. Пустой список — ничего не происходит (ход ничей). */
    fun onTimeout(state: SessionState<G>): List<SessionEvent>

    /** Что сейчас может сделать игрок: варианты ходов в том же конверте, что тело хода. */
    fun moveOptions(state: SessionState<G>, playerId: UUID): List<MoveOption>

    /** Применяет собственное событие игры (тип из [eventClasses]). Один редьюсер и для полного состояния, и для проекций. */
    fun evolve(state: SessionState<G>, event: SessionEvent): SessionState<G>

    /** Данные собственного события игры для проекции [viewer]. По умолчанию событие публичное. */
    fun mask(event: SessionEvent, viewer: ViewerKey): SessionEvent = event

    /** Игровая часть начального состояния (событие `started`) для проекции [viewer]. По умолчанию публичная. */
    fun maskGame(game: G, viewer: ViewerKey): G = game
}

/** Начало партии: игровая часть состояния и очерёдность (модуль turns). */
data class GameStart<G : Any>(
    val game: G,
    val turns: TurnsState,
)

/** Результат хода по правилам игры. */
sealed interface Decision {

    data class Accepted(val events: List<SessionEvent>) : Decision

    /** [code] — машиночитаемая причина, отдаётся клиенту. */
    data class Rejected(val code: String, val message: String) : Decision
}

/** Нарушение в настройках игры: [field] — путь к полю внутри настроек игры, [message] — описание для клиента. */
data class SettingsViolation(val field: String, val message: String)
