package site.qgames.game.table.room

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.json

/** Таблица `room`. Перечисления — строки (имена enum модели, код игры — GameCode.code). */
object RoomTable : Table("room") {
    val id = javaUUID("id")
    val gameCode = varchar("game_code", 32)
    val ownerId = javaUUID("owner_id").nullable()
    val status = varchar("status", 16)
    val currentSessionId = javaUUID("current_session_id").nullable()
    val emptySince = timestamp("empty_since").nullable()
    /** JSON настроек комнаты: room, simple и advanced (RoomSettingsJsonMapper). */
    val settings = json<String>("settings", { it }, { it })
    /** seq последнего события потока комнаты (RoomEventPublisher); в модель комнаты не входит. */
    val eventSeq = long("event_seq")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Таблица `room_member`: участники комнаты в порядке входа. */
object RoomMemberTable : Table("room_member") {
    val roomId = javaUUID("room_id")
    val userId = javaUUID("user_id")
    val role = varchar("role", 16)
    val joinedAt = timestamp("joined_at")
    /** Когда игрок идущей партии закрыл последний поток комнаты; null — участник присутствует. */
    val leftAt = timestamp("left_at").nullable()

    override val primaryKey = PrimaryKey(roomId, userId)
}

/** Таблица `room_connection`: открытые потоки комнат с арендой. */
object RoomConnectionTable : Table("room_connection") {
    val id = javaUUID("id")
    val roomId = javaUUID("room_id")
    val userId = javaUUID("user_id")
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(id)
}
