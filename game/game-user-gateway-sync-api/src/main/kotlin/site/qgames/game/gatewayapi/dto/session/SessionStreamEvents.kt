package site.qgames.game.gatewayapi.dto.session

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import site.qgames.game.gatewayapi.dto.room.SessionStatus
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

/** Как событие меняет документ партии. */
enum class SessionEventMethod {
    /** Заменить или создать значение по пути целиком. */
    PUT,

    /** Неглубокое слияние объекта по пути с value. */
    PATCH,

    /** Удалить поле объекта или элемент массива по id. */
    DELETE,

    /** Документ не меняется: данные для журнала. */
    INFO,
}

/** Методы строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор method). */
object SessionEventMethods {
    const val PUT = "PUT"
    const val PATCH = "PATCH"
    const val DELETE = "DELETE"
    const val INFO = "INFO"
}

/**
 * Событие журнала партии — одна операция над документом партии `{status, meta, state}` (тот же документ, что снапшот
 * без seq и serverTime). Клиент применяет его по [SessionEventMethod], не зная кодов: PUT, PATCH, DELETE по пути
 * `data.path`, INFO состояние не меняет.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "method", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = SessionEventPut::class, name = SessionEventMethods.PUT),
    JsonSubTypes.Type(value = SessionEventPatch::class, name = SessionEventMethods.PATCH),
    JsonSubTypes.Type(value = SessionEventDelete::class, name = SessionEventMethods.DELETE),
    JsonSubTypes.Type(value = SessionEventInfo::class, name = SessionEventMethods.INFO),
)
@Schema(
    description = "Событие журнала партии {seq, at, method, code, data}: одна операция над документом партии " +
        "{status, meta, state}. Состояние меняется только по method (PUT, PATCH, DELETE; INFO не меняет), code — что " +
        "произошло, для журнала и логов. Несуществующий путь у PATCH/DELETE или неподходящий тип — рассинхронизация, " +
        "клиент переподключается за свежим снапшотом.\n\n" + SessionEventCodes.DESCRIPTION,
    oneOf = [SessionEventPut::class, SessionEventPatch::class, SessionEventDelete::class, SessionEventInfo::class],
    discriminatorProperty = "method",
    discriminatorMapping = [
        DiscriminatorMapping(value = SessionEventMethods.PUT, schema = SessionEventPut::class),
        DiscriminatorMapping(value = SessionEventMethods.PATCH, schema = SessionEventPatch::class),
        DiscriminatorMapping(value = SessionEventMethods.DELETE, schema = SessionEventDelete::class),
        DiscriminatorMapping(value = SessionEventMethods.INFO, schema = SessionEventInfo::class),
    ],
)
sealed interface SessionPatchEvent

@Schema(description = "PUT: заменить или создать значение по пути целиком; {id} в конце пути, которого нет, — добавить элемент в конец массива")
data class SessionEventPut(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [SessionEventMethods.PUT])
    val method: SessionEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PutData,
) : SessionPatchEvent

@Schema(description = "PATCH: неглубокое слияние объекта по пути с value (путь должен существовать)")
data class SessionEventPatch(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [SessionEventMethods.PATCH])
    val method: SessionEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PatchData,
) : SessionPatchEvent

@Schema(description = "DELETE: удалить поле объекта или элемент массива по id (путь должен существовать)")
data class SessionEventDelete(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [SessionEventMethods.DELETE])
    val method: SessionEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: DeleteData,
) : SessionPatchEvent

@Schema(description = "INFO: документ не меняется, data — данные для журнала и логов (seq всё равно сдвигается)")
data class SessionEventInfo(
    val seq: Long,
    /** Когда событие записано в журнал. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [SessionEventMethods.INFO])
    val method: SessionEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    @field:Schema(implementation = InfoData::class)
    val data: Map<String, Any?>,
) : SessionPatchEvent

@Schema(description = "Данные PUT: путь и новое значение (любой JSON, в том числе null)")
data class PutData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
    @field:Schema(description = "Новое значение: любой JSON, в том числе null. Схема — схема состояния игры по пути")
    val value: Any?,
)

@Schema(description = "Данные PATCH: путь к объекту и поля для неглубокого слияния")
data class PatchData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
    @field:Schema(description = "Поля, которые заменяют поля объекта по пути (вложенные значения — целиком, null — как значение)")
    val value: Map<String, Any?>,
)

@Schema(description = "Данные DELETE: путь к полю объекта или элементу массива")
data class DeleteData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
)

/** Только схема OpenAPI: данные INFO — произвольный объект (в Kotlin — Map). */
@Schema(
    name = "InfoData",
    description = "Данные INFO: произвольный объект для журнала и логов, состояние не меняет",
    types = ["object"],
    additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
)
class InfoData

/** Только схема OpenAPI: сегмент пути — строка (ключ объекта) или `{id}` (элемент массива по id). */
@Schema(
    name = "PathSegment",
    description = "Сегмент пути: строка — ключ объекта, {id} — элемент массива с этим id (адрес не зависит от индекса)",
    oneOf = [String::class, PathSegmentId::class],
)
class PathSegment

/** Элемент массива по id. */
data class PathSegmentId(
    val id: String,
)

private const val PATH_DESCRIPTION = "Путь от корня документа {status, meta, state}: [] — весь документ, " +
    "[\"state\", \"timer\"], [\"state\", \"players\", {\"id\": \"…\"}, \"moveOptions\"]"

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
        "- timer-started — PUT [\"state\", \"timer\"]: таймер следующего хода;\n" +
        "- game-paused — PATCH [\"state\", \"timer\"] {status: PAUSED, pausedAt}: пауза;\n" +
        "- game-resumed — PATCH [\"state\", \"timer\"] {status: RUNNING, deadline, pausedAt: null}: продолжение;\n" +
        "- timer-stopped — PUT [\"state\", \"timer\"]: таймер остановлен перед концом или отменой партии;\n" +
        "- player-passed («игра в ничто») — PATCH [\"state\"] {moves}: игрок пропустил ход; по истечении времени — " +
        "INFO {playerId, timeout: true}."
}
