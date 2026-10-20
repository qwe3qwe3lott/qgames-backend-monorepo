package site.qgames.game.gatewayapi.dto.room

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Тип события потока комнаты: значение поля `event:` в SSE. */
enum class RoomStreamEventType(@get:JsonValue val code: String) {
    SNAPSHOT(RoomStreamEventTypes.SNAPSHOT),
    USERS_CHANGED(RoomStreamEventTypes.USERS_CHANGED),
    SETTINGS_CHANGED(RoomStreamEventTypes.SETTINGS_CHANGED),
    SESSION_CHANGED(RoomStreamEventTypes.SESSION_CHANGED),
    ROOM_DELETED(RoomStreamEventTypes.ROOM_DELETED),
    ;

    companion object {
        fun fromCode(code: String): RoomStreamEventType? = entries.firstOrNull { it.code == code }
    }
}

/** Типы событий строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор type). */
object RoomStreamEventTypes {
    const val SNAPSHOT = "snapshot"
    const val USERS_CHANGED = "users-changed"
    const val SETTINGS_CHANGED = "settings-changed"
    const val SESSION_CHANGED = "session-changed"
    const val ROOM_DELETED = "room-deleted"
}

/**
 * Событие потока комнаты. В SSE тип передаётся полем `event:`, а [data] — JSON в поле `data:`; конверт `{type, data}`
 * описывает пару целиком, клиент собирает его сам. События несут полное значение (семантика замены), поэтому повтор
 * безопасен.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = RoomSnapshotEvent::class, name = RoomStreamEventTypes.SNAPSHOT),
    JsonSubTypes.Type(value = RoomUsersChangedEvent::class, name = RoomStreamEventTypes.USERS_CHANGED),
    JsonSubTypes.Type(value = RoomSettingsChangedEvent::class, name = RoomStreamEventTypes.SETTINGS_CHANGED),
    JsonSubTypes.Type(value = RoomSessionChangedEvent::class, name = RoomStreamEventTypes.SESSION_CHANGED),
    JsonSubTypes.Type(value = RoomDeletedEvent::class, name = RoomStreamEventTypes.ROOM_DELETED),
)
@Schema(
    description = "Событие потока комнаты: тип — поле event: в SSE, data — JSON в поле data:",
    oneOf = [
        RoomSnapshotEvent::class,
        RoomUsersChangedEvent::class,
        RoomSettingsChangedEvent::class,
        RoomSessionChangedEvent::class,
        RoomDeletedEvent::class,
    ],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = RoomStreamEventTypes.SNAPSHOT, schema = RoomSnapshotEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.USERS_CHANGED, schema = RoomUsersChangedEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.SETTINGS_CHANGED, schema = RoomSettingsChangedEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.SESSION_CHANGED, schema = RoomSessionChangedEvent::class),
        DiscriminatorMapping(value = RoomStreamEventTypes.ROOM_DELETED, schema = RoomDeletedEvent::class),
    ],
)
sealed interface RoomStreamEvent

/** Комната целиком для этого зрителя, как в GET комнаты. Всегда первое сообщение потока, в том числе после переподключения. */
@Schema(description = "Комната целиком для этого зрителя, как в GET комнаты; всегда первое сообщение потока")
data class RoomSnapshotEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.SNAPSHOT])
    val type: RoomStreamEventType,
    val data: RoomResponse,
) : RoomStreamEvent

/** Изменился состав участников, их роли, ники, уход (leaving) или владелец. */
@Schema(description = "Изменился состав участников, их роли, ники, уход (leaving) или владелец")
data class RoomUsersChangedEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.USERS_CHANGED])
    val type: RoomStreamEventType,
    val data: RoomUsersDto,
) : RoomStreamEvent

/** Изменились настройки комнаты. */
@Schema(description = "Изменились настройки комнаты")
data class RoomSettingsChangedEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.SETTINGS_CHANGED])
    val type: RoomStreamEventType,
    val data: RoomSettingsChangedDto,
) : RoomStreamEvent

/** Партия началась, отменена, поставлена на паузу, продолжена или закончилась: клиент перечитывает комнату (GET). */
@Schema(description = "Партия началась, отменена, поставлена на паузу, продолжена или закончилась: клиент перечитывает комнату (GET)")
data class RoomSessionChangedEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.SESSION_CHANGED])
    val type: RoomStreamEventType,
    val data: EmptyEventDataDto,
) : RoomStreamEvent

/** Комната удалена. После события сервер закрывает поток. */
@Schema(description = "Комната удалена; после события сервер закрывает поток")
data class RoomDeletedEvent(
    @field:Schema(type = "string", allowableValues = [RoomStreamEventTypes.ROOM_DELETED])
    val type: RoomStreamEventType,
    val data: EmptyEventDataDto,
) : RoomStreamEvent

/** Владелец и полный список участников комнаты в порядке входа. */
data class RoomUsersDto(
    /** Нет, если в комнате никого не осталось. */
    val ownerId: UUID?,
    val members: List<RoomMemberResponse>,
)

/** Настройки комнаты целиком, как RoomResponse.settings. */
data class RoomSettingsChangedDto(
    val settings: RoomSettingsDto,
)

/** Событие без данных: в потоке — `{}`. */
@Schema(types = ["object"], description = "Событие без данных: {}")
class EmptyEventDataDto {
    override fun equals(other: Any?) = other is EmptyEventDataDto

    override fun hashCode() = 0
}
