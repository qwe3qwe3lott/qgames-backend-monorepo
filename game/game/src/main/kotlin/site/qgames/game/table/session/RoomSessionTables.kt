package site.qgames.game.table.session

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.json

/** Таблица `room_session`: партии. Состояние не хранится — это свёртка журнала [RoomSessionEventTable]. */
object RoomSessionTable : Table("room_session") {
    val id = javaUUID("id")
    val roomId = javaUUID("room_id")
    val gameCode = varchar("game_code", 32)
    val status = varchar("status", 16)
    val seed = long("seed")
    val lastSeq = long("last_seq")
    val timeControlMode = varchar("time_control_mode", 16)
    val moveSeconds = integer("move_seconds").nullable()
    /** Снимок JSON настроек игры на момент старта. */
    val gameSettings = json<String>("game_settings", { it }, { it })
    /** Проекция таймера для MoveTimeoutJob. */
    val moveDeadline = timestamp("move_deadline").nullable()
    val createdAt = timestamp("created_at")
    val finishedAt = timestamp("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Таблица `room_session_player`: места игроков партии. */
object RoomSessionPlayerTable : Table("room_session_player") {
    val sessionId = javaUUID("session_id")
    val seat = integer("seat")
    val userId = javaUUID("user_id")

    override val primaryKey = PrimaryKey(sessionId, seat)
}

/** Таблица `room_session_event`: журнал партии, только дописывается. */
object RoomSessionEventTable : Table("room_session_event") {
    val sessionId = javaUUID("session_id")
    val seq = long("seq")
    /** Имя SessionEventMethod. */
    val method = varchar("method", 8)
    val code = varchar("code", 64)
    /** JSON данных события в форме кадра потока: PUT и PATCH — {path, value}, DELETE — {path}, INFO — объект. */
    val data = json<String>("data", { it }, { it })
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(sessionId, seq)
}
