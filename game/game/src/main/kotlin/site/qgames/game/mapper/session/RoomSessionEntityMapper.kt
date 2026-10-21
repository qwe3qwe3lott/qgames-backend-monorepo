package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.entity.session.RoomSessionEntity
import site.qgames.game.entity.session.RoomSessionEventEntity
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionEventModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.games.api.session.SessionEvent
import java.time.Instant
import java.util.UUID

@Component
class RoomSessionEntityMapper(
    private val sessionEventJsonMapper: SessionEventJsonMapper,
) {

    fun toModel(entity: RoomSessionEntity) = RoomSessionModel(
        id = entity.id,
        roomId = entity.roomId,
        gameCode = GameCode.fromCode(entity.gameCode) ?: error("Unknown game code '${entity.gameCode}'"),
        status = RoomSessionStatus.valueOf(entity.status),
        stateSchemaVersion = entity.stateSchemaVersion,
        seed = entity.seed,
        lastSeq = entity.lastSeq,
        timeControl = RoomTimeControlModel(RoomTimeControlMode.valueOf(entity.timeControlMode), entity.moveSeconds),
        gameSettings = entity.gameSettings,
        moveDeadline = entity.moveDeadline,
        createdAt = entity.createdAt,
        finishedAt = entity.finishedAt,
    )

    /** Событие журнала партии: метод, код и данные (путь и значение — JSON-дерево). */
    fun toModel(entity: RoomSessionEventEntity) = RoomSessionEventModel(
        seq = entity.seq,
        createdAt = entity.createdAt,
        event = sessionEventJsonMapper.read(entity.method, entity.code, entity.data),
    )

    fun toEntity(sessionId: UUID, seq: Long, event: SessionEvent, createdAt: Instant) = RoomSessionEventEntity(
        sessionId = sessionId,
        seq = seq,
        method = event.method.name,
        code = event.code,
        data = sessionEventJsonMapper.write(event),
        createdAt = createdAt,
    )
}
