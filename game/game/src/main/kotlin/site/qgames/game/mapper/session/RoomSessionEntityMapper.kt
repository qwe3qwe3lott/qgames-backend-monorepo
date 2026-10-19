package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.entity.session.RoomSessionMoveEntity
import site.qgames.game.entity.session.RoomSessionEntity
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.session.RoomSessionMoveModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus

@Component
class RoomSessionEntityMapper {

    fun toModel(entity: RoomSessionEntity) = RoomSessionModel(
        id = entity.id,
        roomId = entity.roomId,
        gameType = RoomGameType.valueOf(entity.gameType),
        status = RoomSessionStatus.valueOf(entity.status),
        state = entity.state,
        stateSchemaVersion = entity.stateSchemaVersion,
        seed = entity.seed,
        version = entity.version,
        createdAt = entity.createdAt,
        finishedAt = entity.finishedAt,
    )

    fun toEntity(model: RoomSessionMoveModel) = RoomSessionMoveEntity(
        sessionId = model.sessionId,
        seq = model.seq,
        playerId = model.playerId,
        move = model.move,
        createdAt = model.createdAt,
    )
}
