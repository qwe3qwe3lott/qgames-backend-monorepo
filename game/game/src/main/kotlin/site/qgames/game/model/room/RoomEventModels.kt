package site.qgames.game.model.room

import java.util.UUID

/**
 * Событие комнаты (Postgres NOTIFY, канал room_events). Только сигнал: данные для потока диспетчер читает сам, когда
 * раздаёт событие подписчикам, — уже после коммита изменения.
 */
data class RoomEventModel(
    val roomId: UUID,
    val type: RoomEventType,
    /** Только у [RoomEventType.SUBSCRIBED]: какой подписчик открыл поток. */
    val subscriberId: UUID? = null,
)

/** Значение [code] — тип в JSON уведомления; у событий потока совпадает с типом SSE-события. */
enum class RoomEventType(val code: String) {
    USERS_CHANGED("users-changed"),
    SETTINGS_CHANGED("settings-changed"),
    SESSION_CHANGED("session-changed"),
    ROOM_DELETED("room-deleted"),

    /**
     * Маркер подключения: публикуется в транзакции подключения после чтения снапшота. Всё, что подписчик получил до
     * своего маркера, уже есть в снапшоте.
     */
    SUBSCRIBED("subscribed"),
    ;

    companion object {
        fun fromCode(code: String): RoomEventType? = entries.firstOrNull { it.code == code }
    }
}

/** Результат подключения к потоку комнаты: id подключения и снапшот комнаты для подключившегося. */
data class RoomConnectionModel(
    val connectionId: UUID,
    val details: RoomDetailsModel,
)

/** Владелец и участники комнаты: данные события users-changed. */
data class RoomUsersModel(
    val room: RoomModel,
    /** В порядке входа. */
    val members: List<RoomMemberDetailsModel>,
)
