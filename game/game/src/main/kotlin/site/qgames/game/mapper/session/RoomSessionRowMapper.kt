package site.qgames.game.mapper.session

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.session.RoomSessionEventModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.table.session.RoomSessionEventTable
import site.qgames.game.table.session.RoomSessionTable

/** Строки таблиц `room_session` и `room_session_event` → модели. */
@Component
class RoomSessionRowMapper(
    private val sessionEventJsonMapper: SessionEventJsonMapper,
) {

    fun toSession(row: ResultRow): RoomSessionModel {
        val gameCode = row[RoomSessionTable.gameCode]
        return RoomSessionModel(
            id = row[RoomSessionTable.id],
            roomId = row[RoomSessionTable.roomId],
            gameCode = GameCode.fromCode(gameCode) ?: error("Unknown game code '$gameCode'"),
            status = RoomSessionStatus.valueOf(row[RoomSessionTable.status]),
            seed = row[RoomSessionTable.seed],
            lastSeq = row[RoomSessionTable.lastSeq],
            settings = row[RoomSessionTable.settings],
            moveDeadline = row[RoomSessionTable.moveDeadline],
            createdAt = row[RoomSessionTable.createdAt],
            finishedAt = row[RoomSessionTable.finishedAt],
        )
    }

    /** Событие журнала партии: метод, код и данные (путь и значение — JSON-дерево). */
    fun toEvent(row: ResultRow) = RoomSessionEventModel(
        seq = row[RoomSessionEventTable.seq],
        createdAt = row[RoomSessionEventTable.createdAt],
        event = sessionEventJsonMapper.read(row[RoomSessionEventTable.method], row[RoomSessionEventTable.code], row[RoomSessionEventTable.data]),
    )
}
