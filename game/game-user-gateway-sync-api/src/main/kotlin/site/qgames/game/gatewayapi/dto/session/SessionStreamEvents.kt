package site.qgames.game.gatewayapi.dto.session

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import site.qgames.game.gatewayapi.dto.room.EmptyEventDataDto
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import java.time.Instant

/** Тип события потока партии: значение поля `event:` в SSE. */
enum class SessionStreamEventType(@get:JsonValue val code: String) {
    SNAPSHOT(SessionStreamEventTypes.SNAPSHOT),
    STARTED(SessionStreamEventTypes.STARTED),
    SESSION_FINISHED(SessionStreamEventTypes.SESSION_FINISHED),
    SESSION_CANCELLED(SessionStreamEventTypes.SESSION_CANCELLED),
    PLAYER_CHANGED(SessionStreamEventTypes.PLAYER_CHANGED),
    TURNS_CHANGED(SessionStreamEventTypes.TURNS_CHANGED),
    TIMER_CHANGED(SessionStreamEventTypes.TIMER_CHANGED),
    MOVED(SessionStreamEventTypes.MOVED),
    ;

    companion object {
        fun fromCode(code: String): SessionStreamEventType? = entries.firstOrNull { it.code == code }
    }
}

/** Типы событий строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор type). */
object SessionStreamEventTypes {
    const val SNAPSHOT = "snapshot"
    const val STARTED = "started"
    const val SESSION_FINISHED = "session-finished"
    const val SESSION_CANCELLED = "session-cancelled"
    const val PLAYER_CHANGED = "player-changed"
    const val TURNS_CHANGED = "turns-changed"
    const val TIMER_CHANGED = "timer-changed"
    const val MOVED = "moved"
}

/**
 * Событие потока партии. В SSE тип передаётся полем `event:`, а [data] — JSON в поле `data:`; конверт `{type, data}`
 * описывает пару целиком, клиент собирает его сам.
 *
 * Первое сообщение — snapshot (состояние для этого зрителя на seq), дальше события журнала в кадре `{seq, at, data}`
 * с непрерывным seq. Замаскированное для зрителя событие тоже доставляется, поэтому дыра в seq — реальная потеря.
 * События модулей — замена блока целиком (PUT). Данные игровых событий и состояние зависят от игры
 * (meta.game.code).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = SessionSnapshotEvent::class, name = SessionStreamEventTypes.SNAPSHOT),
    JsonSubTypes.Type(value = SessionStartedEvent::class, name = SessionStreamEventTypes.STARTED),
    JsonSubTypes.Type(value = SessionFinishedEvent::class, name = SessionStreamEventTypes.SESSION_FINISHED),
    JsonSubTypes.Type(value = SessionCancelledEvent::class, name = SessionStreamEventTypes.SESSION_CANCELLED),
    JsonSubTypes.Type(value = SessionPlayerChangedEvent::class, name = SessionStreamEventTypes.PLAYER_CHANGED),
    JsonSubTypes.Type(value = SessionTurnsChangedEvent::class, name = SessionStreamEventTypes.TURNS_CHANGED),
    JsonSubTypes.Type(value = SessionTimerChangedEvent::class, name = SessionStreamEventTypes.TIMER_CHANGED),
    JsonSubTypes.Type(value = SessionMovedEvent::class, name = SessionStreamEventTypes.MOVED),
)
@Schema(
    description = "Событие потока партии: тип — поле event: в SSE, data — JSON в поле data:",
    oneOf = [
        SessionSnapshotEvent::class,
        SessionStartedEvent::class,
        SessionFinishedEvent::class,
        SessionCancelledEvent::class,
        SessionPlayerChangedEvent::class,
        SessionTurnsChangedEvent::class,
        SessionTimerChangedEvent::class,
        SessionMovedEvent::class,
    ],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = SessionStreamEventTypes.SNAPSHOT, schema = SessionSnapshotEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.STARTED, schema = SessionStartedEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.SESSION_FINISHED, schema = SessionFinishedEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.SESSION_CANCELLED, schema = SessionCancelledEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.PLAYER_CHANGED, schema = SessionPlayerChangedEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.TURNS_CHANGED, schema = SessionTurnsChangedEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.TIMER_CHANGED, schema = SessionTimerChangedEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.MOVED, schema = SessionMovedEvent::class),
    ],
)
sealed interface SessionStreamEvent

@Schema(description = "Состояние партии для этого зрителя на seq; всегда первое сообщение потока")
data class SessionSnapshotEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.SNAPSHOT])
    val type: SessionStreamEventType,
    val data: SessionSnapshotDto,
) : SessionStreamEvent

@Schema(description = "Первое событие журнала: описание партии и начальное состояние. Обычно уже входит в снапшот")
data class SessionStartedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.STARTED])
    val type: SessionStreamEventType,
    val data: SessionStartedFrame,
) : SessionStreamEvent

@Schema(description = "Партия закончилась по правилам; после события сервер закрывает поток")
data class SessionFinishedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.SESSION_FINISHED])
    val type: SessionStreamEventType,
    val data: SessionEndedFrame,
) : SessionStreamEvent

@Schema(description = "Партию отменил владелец; после события сервер закрывает поток")
data class SessionCancelledEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.SESSION_CANCELLED])
    val type: SessionStreamEventType,
    val data: SessionEndedFrame,
) : SessionStreamEvent

@Schema(description = "Модуль core: игрок целиком (замена по id); чужим зрителям moveOptions — пустой список")
data class SessionPlayerChangedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.PLAYER_CHANGED])
    val type: SessionStreamEventType,
    val data: SessionPlayerChangedFrame,
) : SessionStreamEvent

@Schema(description = "Модуль turns: блок очерёдности целиком (чей ход, раунд)")
data class SessionTurnsChangedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.TURNS_CHANGED])
    val type: SessionStreamEventType,
    val data: SessionTurnsChangedFrame,
) : SessionStreamEvent

@Schema(description = "Модуль timer: таймер целиком, в том числе пауза и продолжение")
data class SessionTimerChangedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.TIMER_CHANGED])
    val type: SessionStreamEventType,
    val data: SessionTimerChangedFrame,
) : SessionStreamEvent

@Schema(description = "Игра: игрок сделал ход; данные задаёт игра (meta.game.code)")
data class SessionMovedEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.MOVED])
    val type: SessionStreamEventType,
    val data: SessionMovedFrame,
) : SessionStreamEvent

/** Снапшот: состояние партии для зрителя после события [seq]. Следующее событие потока — seq + 1. */
data class SessionSnapshotDto(
    val seq: Long,
    val status: SessionStatus,
    /** Время сервера на момент снапшота: по нему клиент поправляет часы для обратного отсчёта. */
    val serverTime: Instant,
    val meta: SessionMetaDto,
    val state: SessionStateDto,
)

/** Данные started: описание партии и начальное состояние. */
data class SessionStartedDto(
    val meta: SessionMetaDto,
    val state: SessionStateDto,
)

/** Кадр события started. [at] — когда событие записано в журнал. */
data class SessionStartedFrame(
    val seq: Long,
    val at: Instant,
    val data: SessionStartedDto,
)

/** Кадр session-finished и session-cancelled: данных нет. */
data class SessionEndedFrame(
    val seq: Long,
    val at: Instant,
    val data: EmptyEventDataDto,
)

/** Кадр player-changed. */
data class SessionPlayerChangedFrame(
    val seq: Long,
    val at: Instant,
    val data: SessionPlayerDto,
)

/** Кадр turns-changed. */
data class SessionTurnsChangedFrame(
    val seq: Long,
    val at: Instant,
    val data: SessionTurnsDto,
)

/** Кадр timer-changed. */
data class SessionTimerChangedFrame(
    val seq: Long,
    val at: Instant,
    val data: SessionTimerDto,
)

/** Кадр moved. */
data class SessionMovedFrame(
    val seq: Long,
    val at: Instant,
    val data: SessionMovedDto,
)
