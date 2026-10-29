package site.qgames.game.api.dto.room

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
import java.time.Instant

/** Тип сообщения потока комнаты: значение поля `event:` в SSE (у событий — метод в нижнем регистре). */
enum class RoomStreamEventType(@get:JsonValue val code: String) {
    SNAPSHOT(RoomStreamEventTypes.SNAPSHOT),
    PUT(RoomStreamEventTypes.PUT),
    PATCH(RoomStreamEventTypes.PATCH),
    DELETE(RoomStreamEventTypes.DELETE),
    INFO(RoomStreamEventTypes.INFO),
    ;

    companion object {
        fun fromCode(code: String): RoomStreamEventType? = entries.firstOrNull { it.code == code }
    }
}

/** Типы сообщений строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор type). */
object RoomStreamEventTypes {
    const val SNAPSHOT = "snapshot"
    const val PUT = "put"
    const val PATCH = "patch"
    const val DELETE = "delete"
    const val INFO = "info"
}

/**
 * Сообщение потока комнаты. В SSE тип передаётся полем `event:`, а [data] — JSON в поле `data:`; конверт `{type, data}`
 * описывает пару целиком, клиент собирает его сам.
 *
 * Первое сообщение — snapshot (seq и комната — документ потока), дальше события-патчи документа
 * `{seq, at, method, code, data}` с непрерывным seq ([RoomPatchEvent]); тип сообщения — метод в нижнем регистре.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = RoomSnapshotEvent::class, name = RoomStreamEventTypes.SNAPSHOT),
    JsonSubTypes.Type(
        value = RoomPatchStreamEvent::class,
        names = [RoomStreamEventTypes.PUT, RoomStreamEventTypes.PATCH, RoomStreamEventTypes.DELETE, RoomStreamEventTypes.INFO],
    ),
)
@Schema(
    description = "Сообщение потока комнаты: тип — поле event: в SSE, data — JSON в поле data:. snapshot — seq и " +
        "комната (документ потока), put/patch/delete/info — событие-патч документа",
    oneOf = [RoomSnapshotEvent::class, RoomPatchStreamEvent::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = RoomStreamEventTypes.SNAPSHOT, schema = RoomSnapshotEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.PUT, schema = RoomPatchStreamEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.PATCH, schema = RoomPatchStreamEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.DELETE, schema = RoomPatchStreamEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.INFO, schema = RoomPatchStreamEvent::class),
    ],
)
sealed interface RoomStreamEvent

@Schema(description = "seq и комната для этого зрителя на seq; всегда первое сообщение потока, в том числе после переподключения")
data class RoomSnapshotEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.SNAPSHOT])
    val type: RoomStreamEventType,
    val data: RoomSnapshotDto,
) : RoomStreamEvent

@Schema(description = "Событие-патч комнаты; тип сообщения — метод события в нижнем регистре")
data class RoomPatchStreamEvent(
    @field:Schema(
        type = "string",
        allowableValues = [RoomStreamEventTypes.PUT, RoomStreamEventTypes.PATCH, RoomStreamEventTypes.DELETE, RoomStreamEventTypes.INFO],
    )
    val type: RoomStreamEventType,
    val data: RoomPatchEvent,
) : RoomStreamEvent

/** Снапшот: документ потока — комната [room] (как в GET комнаты) после события [seq]. Следующее событие потока — seq + 1. */
data class RoomSnapshotDto(
    val seq: Long,
    val room: RoomResponse,
)

/**
 * Событие потока комнаты — одна операция над документом потока (комната, как [RoomSnapshotDto.room]). Клиент применяет
 * его по [PatchEventMethod], не зная кодов: PUT, PATCH, DELETE по пути `data.path`, INFO документ не меняет.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "method", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = RoomEventPut::class, name = PatchEventMethods.PUT),
    JsonSubTypes.Type(value = RoomEventPatch::class, name = PatchEventMethods.PATCH),
    JsonSubTypes.Type(value = RoomEventDelete::class, name = PatchEventMethods.DELETE),
    JsonSubTypes.Type(value = RoomEventInfo::class, name = PatchEventMethods.INFO),
)
@Schema(
    description = "Событие потока комнаты {seq, at, method, code, data}: одна операция над документом потока " +
        "(комната). Документ меняется только по method (PUT, PATCH, DELETE; INFO не меняет), code — что произошло. " +
        "Несуществующий путь у PATCH/DELETE, неподходящий тип или разрыв seq — рассинхронизация, клиент " +
        "переподключается за свежим снапшотом. Составное изменение — несколько событий подряд (уход владельца — " +
        "member-left и owner-changed).\n\n" + RoomEventCodes.DESCRIPTION,
    oneOf = [RoomEventPut::class, RoomEventPatch::class, RoomEventDelete::class, RoomEventInfo::class],
    discriminatorProperty = "method",
    discriminatorMapping = [
        DiscriminatorMapping(value = PatchEventMethods.PUT, schema = RoomEventPut::class),
        DiscriminatorMapping(value = PatchEventMethods.PATCH, schema = RoomEventPatch::class),
        DiscriminatorMapping(value = PatchEventMethods.DELETE, schema = RoomEventDelete::class),
        DiscriminatorMapping(value = PatchEventMethods.INFO, schema = RoomEventInfo::class),
    ],
)
sealed interface RoomPatchEvent

@Schema(description = "PUT: заменить или создать значение по пути целиком; {id} в конце пути, которого нет, — добавить элемент в конец массива")
data class RoomEventPut(
    val seq: Long,
    /** Когда произошло изменение. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.PUT])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PutData,
) : RoomPatchEvent

@Schema(description = "PATCH: неглубокое слияние объекта по пути с value (путь должен существовать)")
data class RoomEventPatch(
    val seq: Long,
    /** Когда произошло изменение. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.PATCH])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: PatchData,
) : RoomPatchEvent

@Schema(description = "DELETE: удалить поле объекта или элемент массива по id (путь должен существовать)")
data class RoomEventDelete(
    val seq: Long,
    /** Когда произошло изменение. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.DELETE])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    val data: DeleteData,
) : RoomPatchEvent

@Schema(description = "INFO: документ не меняется, data — данные события (seq всё равно сдвигается)")
data class RoomEventInfo(
    val seq: Long,
    /** Когда произошло изменение. */
    val at: Instant,
    @field:Schema(type = "string", allowableValues = [PatchEventMethods.INFO])
    val method: PatchEventMethod,
    @field:Schema(description = CODE_DESCRIPTION)
    val code: String,
    @field:Schema(implementation = InfoData::class)
    val data: Map<String, Any?>,
) : RoomPatchEvent

private const val CODE_DESCRIPTION = "Что произошло (см. описание RoomPatchEvent), документ по коду не меняется"

/** Коды событий потока комнаты: для описания в OpenAPI и для сервера. */
object RoomEventCodes {
    const val MEMBER_JOINED = "member-joined"
    const val MEMBER_LEFT = "member-left"
    const val MEMBER_LEAVING = "member-leaving"
    const val MEMBER_RETURNED = "member-returned"
    const val MEMBER_KICKED = "member-kicked"
    const val ROLE_CHANGED = "role-changed"
    const val PROFILE_CHANGED = "profile-changed"
    const val OWNER_CHANGED = "owner-changed"
    const val SETTINGS_CHANGED = "settings-changed"
    const val SESSION_STARTED = "session-started"
    const val SESSION_ENDED = "session-ended"
    const val ROOM_DELETED = "room-deleted"

    const val DESCRIPTION = "Коды (code):\n" +
        "- member-joined — PUT [\"members\", {id}] участник: новый участник комнаты (в конец списка);\n" +
        "- member-left — DELETE [\"members\", {id}]: участник ушёл из комнаты, в том числе отсутствующий игрок после " +
        "конца партии;\n" +
        "- member-leaving — PATCH [\"members\", {id}] {leaving: true}: игрок идущей партии отключился, место за ним;\n" +
        "- member-returned — PATCH [\"members\", {id}] {leaving: false}: отсутствующий игрок партии вернулся;\n" +
        "- member-kicked — INFO {userId}: владелец исключил участника, следом идёт member-left или member-leaving " +
        "(игрок идущей партии). У исключённого это последнее событие: сервер закрывает его потоки комнаты и партии;\n" +
        "- role-changed — PATCH [\"members\", {id}] {role}: участник стал игроком или наблюдателем;\n" +
        "- profile-changed — PATCH [\"members\", {id}] {nickname, avatar}: участник сменил ник или аватар;\n" +
        "- owner-changed — PATCH [] {ownerId}: сменился владелец (null — присутствующих нет);\n" +
        "- settings-changed — PUT [\"settings\"] настройки: настройки комнаты целиком;\n" +
        "- session-started — PATCH [] {status: IN_GAME, session}: началась партия, клиент подписывается на поток " +
        "партии session.id;\n" +
        "- session-ended — PATCH [] {status: WAITING, session}: партия закончилась или отменена (session — её итоговый " +
        "статус);\n" +
        "- room-deleted — INFO {}: комната удалена, последнее событие, сервер закрывает поток."
}
