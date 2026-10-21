package site.qgames.games.api.session

import site.qgames.games.api.TimeControl
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsState
import java.util.UUID

/**
 * Событие журнала партии: тип и данные. Данные — data-класс платформы, модуля или игры (класс определяется по типу).
 * У каждого записанного события свой seq и момент записи; их хранит журнал, а не событие.
 */
data class SessionEvent(
    val type: String,
    val data: Any,
)

/** Состояние партии: блоки модулей и игровая часть [game] (формат задаёт игра). */
data class SessionState<G : Any>(
    /** Модуль core: игроки в порядке мест. */
    val players: List<PlayerState>,
    /** Модуль timer: null — партия без таймера. */
    val timer: TimerState?,
    /** Модуль turns: очерёдность и раунды. */
    val turns: TurnsState,
    val game: G,
)

/** Партия как свёртка журнала: описание, статус и состояние. */
data class Session(
    val meta: SessionMeta,
    val status: SessionStatus,
    val state: SessionState<Any>,
)

enum class SessionStatus {
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}

/** Неизменное описание партии, закреплённое при старте: нужно и для повтора. */
data class SessionMeta(
    val game: GameMeta,
)

/** Игра партии: код и правила (настройки) на момент старта. */
data class GameMeta(
    val code: String,
    val rules: SessionRules,
)

/** Правила партии: время и настройки игры (класс — GameRules.settingsClass). */
data class SessionRules(
    val timeControl: TimeControl,
    val settings: Any,
)

/** Данные первого события журнала `started`: описание партии и начальное состояние. */
data class SessionStarted(
    val meta: SessionMeta,
    val state: SessionState<Any>,
)

/**
 * Для кого собирается состояние: [Full] — полное (проверка ходов сервером; после конца партии его видят все),
 * [Public] — проекция наблюдателя, [Player] — проекция игрока. Ключ проекции, а не подключения: одно состояние
 * годится всем зрителям с тем же ключом.
 */
sealed interface ViewerKey {

    /** Строковый ключ (кеш состояний). */
    val key: String

    /** Видит ли зритель закрытые данные игрока [playerId]. */
    fun sees(playerId: UUID): Boolean

    data object Full : ViewerKey {
        override val key = "FULL"
        override fun sees(playerId: UUID) = true
    }

    data object Public : ViewerKey {
        override val key = "PUBLIC"
        override fun sees(playerId: UUID) = false
    }

    data class Player(val id: UUID) : ViewerKey {
        override val key: String get() = id.toString()
        override fun sees(playerId: UUID) = playerId == id
    }
}

/** Типы событий платформы и модулей и их конструкторы. */
object SessionEvents {

    /** Платформа: первое событие журнала, данные — [SessionStarted]. */
    const val STARTED = "started"

    /** Платформа: партия закончилась по правилам. Данных нет. */
    const val FINISHED = "session-finished"

    /** Платформа: партию отменил владелец. Данных нет. */
    const val CANCELLED = "session-cancelled"

    /** Модуль core: игрок целиком ([PlayerState]), замена по id. */
    const val PLAYER_CHANGED = "player-changed"

    /** Модуль turns: блок очерёдности целиком ([TurnsState]). */
    const val TURNS_CHANGED = "turns-changed"

    /** Модуль timer: таймер целиком ([TimerState]), в том числе пауза и продолжение. */
    const val TIMER_CHANGED = "timer-changed"

    /** Классы данных событий платформы и модулей (кроме [STARTED]: в нём части игры). */
    val dataClasses: Map<String, Class<*>> = mapOf(
        FINISHED to Map::class.java,
        CANCELLED to Map::class.java,
        PLAYER_CHANGED to PlayerState::class.java,
        TURNS_CHANGED to TurnsState::class.java,
        TIMER_CHANGED to TimerState::class.java,
    )

    /** Данные событий без данных: в JSON — `{}`. */
    val NO_DATA: Map<String, Any?> = emptyMap()

    fun finished() = SessionEvent(FINISHED, NO_DATA)

    fun cancelled() = SessionEvent(CANCELLED, NO_DATA)

    fun turnsChanged(turns: TurnsState) = SessionEvent(TURNS_CHANGED, turns)
}
