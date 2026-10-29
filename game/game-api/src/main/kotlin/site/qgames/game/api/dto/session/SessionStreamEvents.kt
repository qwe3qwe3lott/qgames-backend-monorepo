package site.qgames.game.api.dto.session

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import site.qgames.game.api.dto.patch.DeleteData
import site.qgames.game.api.dto.patch.InfoData
import site.qgames.game.api.dto.patch.PatchData
import site.qgames.game.api.dto.patch.PatchEventMethod
import site.qgames.game.api.dto.patch.PatchEventMethods
import site.qgames.game.api.dto.patch.PutData
import site.qgames.game.api.dto.room.SessionStatus
import java.time.Instant

/** Тип сообщения потока партии: значение поля `event:` в SSE (у событий — метод в нижнем регистре). */
enum class SessionStreamEventType(@get:JsonValue val code: String) {
    SNAPSHOT(SessionStreamEventTypes.SNAPSHOT),
    PUT(SessionStreamEventTypes.PUT),
    PATCH(SessionStreamEventTypes.PATCH),
    DELETE(SessionStreamEventTypes.DELETE),
    INFO(SessionStreamEventTypes.INFO),
    ;

    companion object {
        fun fromCode(code: String): SessionStreamEventType? = entries.firstOrNull { it.code == code }
    }
}

/** Типы сообщений строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор type). */
object SessionStreamEventTypes {
    const val SNAPSHOT = "snapshot"
    const val PUT = "put"
    const val PATCH = "patch"
    const val DELETE = "delete"
    const val INFO = "info"
}

/**
 * Сообщение потока партии. В SSE тип передаётся полем `event:`, а [data] — JSON в поле `data:`; конверт `{type, data}`
 * описывает пару целиком, клиент собирает его сам.
 *
 * Первое сообщение — snapshot (документ партии для этого зрителя на seq), дальше события журнала — патчи документа
 * `{seq, at, method, code, data}` с непрерывным seq ([SessionPatchEvent]); тип сообщения — метод в нижнем регистре.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = SessionSnapshotEvent::class, name = SessionStreamEventTypes.SNAPSHOT),
    JsonSubTypes.Type(
        value = SessionPatchStreamEvent::class,
        names = [SessionStreamEventTypes.PUT, SessionStreamEventTypes.PATCH, SessionStreamEventTypes.DELETE, SessionStreamEventTypes.INFO],
    ),
)
@Schema(
    description = "Сообщение потока партии: тип — поле event: в SSE, data — JSON в поле data:. snapshot — документ " +
        "партии, put/patch/delete/info — событие журнала (патч документа)",
    oneOf = [SessionSnapshotEvent::class, SessionPatchStreamEvent::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = SessionStreamEventTypes.SNAPSHOT, schema = SessionSnapshotEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.PUT, schema = SessionPatchStreamEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.PATCH, schema = SessionPatchStreamEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.DELETE, schema = SessionPatchStreamEvent::class),
        DiscriminatorMapping(value = SessionStreamEventTypes.INFO, schema = SessionPatchStreamEvent::class),
    ],
)
sealed interface SessionStreamEvent

@Schema(description = "Документ партии для этого зрителя на seq; всегда первое сообщение потока")
data class SessionSnapshotEvent(
    @field:Schema(type = "string", allowableValues = [SessionStreamEventTypes.SNAPSHOT])
    val type: SessionStreamEventType,
    val data: SessionSnapshotDto,
) : SessionStreamEvent

@Schema(description = "Событие журнала партии; тип сообщения — метод события в нижнем регистре")
data class SessionPatchStreamEvent(
    @field:Schema(
        type = "string",
        allowableValues = [SessionStreamEventTypes.PUT, SessionStreamEventTypes.PATCH, SessionStreamEventTypes.DELETE, SessionStreamEventTypes.INFO],
    )
    val type: SessionStreamEventType,
    val data: SessionPatchEvent,
) : SessionStreamEvent

/** Снапшот: документ партии `{status, meta, state}` для зрителя после события [seq]. Следующее событие потока — seq + 1. */
data class SessionSnapshotDto(
    val seq: Long,
    val status: SessionStatus,
    /** Время сервера на момент снапшота: по нему клиент поправляет часы для обратного отсчёта. */
    val serverTime: Instant,
    val meta: SessionMetaDto,
    val state: SessionStateDto,
)

/**
 * Событие журнала партии — одна операция над документом партии `{status, meta, state}` (тот же документ, что снапшот
 * без seq и serverTime). Клиент применяет его по [PatchEventMethod], не зная кодов: PUT, PATCH, DELETE по пути
 * `data.path`, INFO состояние не меняет.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "method", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = SessionEventPut::class, name = PatchEventMethods.PUT),
    JsonSubTypes.Type(value = SessionEventPatch::class, name = PatchEventMethods.PATCH),
    JsonSubTypes.Type(value = SessionEventDelete::class, name = PatchEventMethods.DELETE),
    JsonSubTypes.Type(value = SessionEventInfo::class, name = PatchEventMethods.INFO),
)
@Schema(
    description = "Событие журнала партии {seq, at, method, code, data}: одна операция над документом партии " +
        "{status, meta, state}. Состояние меняется только по method (PUT, PATCH, DELETE; INFO не меняет), code — что " +
        "произошло, для журнала и логов. Несуществующий путь у PATCH/DELETE или неподходящий тип — рассинхронизация, " +
        "клиент переподключается за свежим снапшотом.\n\n" + SessionEventCodes.DESCRIPTION,
    oneOf = [SessionEventPut::class, SessionEventPatch::class, SessionEventDelete::class, SessionEventInfo::class],
    discriminatorProperty = "method",
    discriminatorMapping = [
        DiscriminatorMapping(value = PatchEventMethods.PUT, schema = SessionEventPut::class),
        DiscriminatorMapping(value = PatchEventMethods.PATCH, schema = SessionEventPatch::class),
        DiscriminatorMapping(value = PatchEventMethods.DELETE, schema = SessionEventDelete::class),
        DiscriminatorMapping(value = PatchEventMethods.INFO, schema = SessionEventInfo::class),
    ],
)
sealed interface SessionPatchEvent

@Schema(description = "PUT: заменить или создать значение по пути целиком; {id} в конце пути, которого нет, — добавить элемент в конец массива")
data class SessionEventPut(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.PUT])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PutData,
) : SessionPatchEvent

@Schema(description = "PATCH: неглубокое слияние объекта по пути с value (путь должен существовать)")
data class SessionEventPatch(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.PATCH])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PatchData,
) : SessionPatchEvent

@Schema(description = "DELETE: удалить поле объекта или элемент массива по id (путь должен существовать)")
data class SessionEventDelete(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.DELETE])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: DeleteData,
) : SessionPatchEvent

@Schema(description = "INFO: документ не меняется, data — данные для журнала и логов (seq всё равно сдвигается)")
data class SessionEventInfo(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.INFO])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    @field:Schema(implementation = InfoData::class)
    val data: Map<String, Any?>,
) : SessionPatchEvent

private const val CODE_DESCRIPTION = "Что произошло (см. описание SessionPatchEvent); набор кодов зависит от игры и её " +
    "версии, состояние по коду не меняется"

/** Известные коды событий: для описания в OpenAPI (enum нет — набор зависит от игры и её версии). */
object SessionEventCodes {
    const val DESCRIPTION = "Известные коды (code):\n" +
        "- session-started — PUT [] {status, meta, state}: первое событие журнала (обычно уже в снапшоте);\n" +
        "- session-finished — PATCH [] {status: FINISHED}: партия закончилась, последнее событие, поток закрывается;\n" +
        "- session-cancelled — PATCH [] {status: CANCELLED}: партию отменил владелец, последнее событие, поток закрывается;\n" +
        "- move-options-changed — PUT [\"state\", \"players\", {id}, \"moveOptions\"]: варианты ходов игрока " +
        "(чужим зрителям — пустой список);\n" +
        "- turn-changed — PUT [\"state\", \"turns\"]: сменился текущий игрок или раунд;\n" +
        "- clock-switched — PUT [\"state\", \"timer\"]: часы после смены очереди (запас ходившего с инкрементом, если он есть, " +
        "дедлайн следующего);\n" +
        "- game-paused — PATCH [\"state\", \"timer\"] {status: PAUSED, pausedAt}: пауза;\n" +
        "- game-resumed — PATCH [\"state\", \"timer\"] {status: RUNNING, deadline, pausedAt: null}: продолжение;\n" +
        "- timer-stopped — PUT [\"state\", \"timer\"]: часы остановлены перед концом или отменой партии (итоговые " +
        "остатки);\n" +
        "- result-set — PUT [\"state\", \"result\"] {outcome, winnerIds, reason}: итог партии, только перед " +
        "session-finished;\n" +
        "- player-passed («игра в ничто») — PATCH [\"state\"] {moves}: игрок пропустил ход; по истечении времени — " +
        "INFO {playerId, timeout: true};\n" +
        "- piece-moved (шашки) — PATCH [\"state\", \"pieces\", {id}] {square}: шашка встала на последнюю клетку пути;\n" +
        "- piece-captured (шашки) — DELETE [\"state\", \"pieces\", {id}]: шашка сбита (на каждую сбитую);\n" +
        "- piece-crowned (шашки) — PATCH [\"state\", \"pieces\", {id}] {kind: KING}: шашка стала дамкой;\n" +
        "- last-move-changed (шашки) — PUT [\"state\", \"lastMove\"] {pieceId, path, captured}: последний ход;\n" +
        "- counters-changed (шашки) — PATCH [\"state\"] {quietMoves, materialMoves}: счётчики ходов дамками без взятий " +
        "и ходов с последнего взятия или превращения;\n" +
        "- position-recorded (шашки) — PUT [\"state\", \"positionHistory\"]: история позиций для повторения;\n" +
        "- draw-offer-changed (шашки) — PATCH [\"state\"] {drawOfferedBy}: ничья предложена ходом или снята ходом " +
        "соперника;\n" +
        "- draw-accepted (шашки) — INFO {playerId}: игрок принял ничью;\n" +
        "- player-resigned (шашки) — INFO {playerId}: игрок сдался;\n" +
        "- move-timed-out (шашки) — INFO {playerId}: время хода игрока вышло (поражение)."
}
