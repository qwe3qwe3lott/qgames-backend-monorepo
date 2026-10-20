package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.entity.session.RoomSessionEntity
import site.qgames.game.entity.session.RoomSessionMoveEntity
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionMoveModel
import site.qgames.game.model.session.RoomSessionStatus

@Component
class RoomSessionEntityMapper {

    fun toModel(entity: RoomSessionEntity) = RoomSessionModel(
        id = entity.id,
        roomId = entity.roomId,
        gameCode = GameCode.fromCode(entity.gameCode) ?: error("Unknown game code '${entity.gameCode}'"),
        status = RoomSessionStatus.valueOf(entity.status),
        state = entity.state,
        stateSchemaVersion = entity.stateSchemaVersion,
        seed = entity.seed,
        version = entity.version,
        timeControl = RoomTimeControlModel(RoomTimeControlMode.valueOf(entity.timeControlMode), entity.moveSeconds),
        gameSettings = entity.gameSettings,
        moveDeadline = entity.moveDeadline,
        pausedAt = entity.pausedAt,
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
