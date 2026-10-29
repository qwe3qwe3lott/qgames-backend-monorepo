package site.qgames.games.core

import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchOperation
import site.qgames.common.docpatch.PathSegment
import java.util.UUID

/**
 * Событие журнала партии — одна операция над документом партии `{status, meta, state}` (DocumentPatch).
 *
 * - [code] — что произошло (`player-passed`, `turn-changed`, `game-paused`…): задаёт платформа (core) или игра, набор
 *   кодов свободно расширяется. Состояние по коду не меняется — только по [method];
 * - [method] — как меняется документ: [PatchMethod.PUT], [PatchMethod.PATCH], [PatchMethod.DELETE] или
 *   [PatchMethod.INFO] (ничего не меняет, [value] — данные для журнала);
 * - [path] — куда: ключи объектов и элементы массивов по id ([PathSegment]); у INFO пустой;
 * - [value] — новое значение (PUT), поля для слияния (PATCH), данные для журнала (INFO); у DELETE null.
 *
 * Значение, которое отдаёт [SessionEngine], — JSON-дерево (Map, List, String, Number, Boolean, null) в том виде, в каком
 * оно уходит клиенту и хранится в журнале. У каждого записанного события свой seq и момент записи; их хранит журнал.
 */
data class SessionEvent(
    override val code: String,
    override val method: PatchMethod,
    override val path: List<PathSegment>,
    override val value: Any?,
) : PatchOperation

/**
 * Документ партии — то, к чему применяются события: `{status, meta, state}` в виде JSON-дерева, ровно как его видит
 * клиент в снапшоте. [session] — его типизированное чтение (проверка ходов, таймер).
 */
data class SessionDocument(
    val tree: Map<String, Any?>,
    val session: Session,
)

/**
 * Состояние партии: блоки core и игровая часть [game] (формат задаёт игра). В документе поля игры лежат в `state`
 * рядом с блоками core (`players`, `timer`, `turns`, `result`), а не отдельным объектом.
 */
data class SessionState<G : Any>(
    /** Игроки в порядке мест. */
    val players: List<PlayerState>,
    /** Часы: null — партия без таймера. */
    val timer: TimerState?,
    /** Очерёдность и раунды. */
    val turns: TurnsState,
    /** Итог партии; null — партия не закончена по правилам (идёт или отменена). */
    val result: ResultState?,
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
    /** Итоговые правила партии для подсказок клиенту (GameRules.advancedRules) как JSON-дерево; null — у игры их нет. */
    val advancedRules: Any? = null,
)

/**
 * Игра партии: код и advanced-настройки на момент старта. [settings] — настройки (GameRules.advancedClass) с кодом игры
 * в поле `gameCode`, как в настройках комнаты.
 */
data class GameMeta(
    val code: String,
    val settings: Map<String, Any?>,
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
