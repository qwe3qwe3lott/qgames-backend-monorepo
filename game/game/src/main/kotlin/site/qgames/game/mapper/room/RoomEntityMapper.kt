package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.entity.room.RoomEntity
import site.qgames.game.entity.room.RoomMemberEntity
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomOwnerGraceModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel

@Component
class RoomEntityMapper {

    fun toModel(entity: RoomEntity) = RoomModel(
        id = entity.id,
        gameCode = GameCode.fromCode(entity.gameCode) ?: error("Unknown game code '${entity.gameCode}'"),
        ownerId = entity.ownerId,
        ownerGrace = entity.ownerGraceUserId?.let { RoomOwnerGraceModel(it, entity.ownerGraceDeadline!!) },
        status = RoomStatus.valueOf(entity.status),
        currentSessionId = entity.currentSessionId,
        emptySince = entity.emptySince,
        timeControl = RoomTimeControlModel(RoomTimeControlMode.valueOf(entity.timeControlMode), entity.moveSeconds),
        gameSettings = entity.gameSettings,
        createdAt = entity.createdAt,
    )

    fun toEntity(model: RoomModel) = RoomEntity(
        id = model.id,
        gameCode = model.gameCode.code,
        ownerId = model.ownerId,
        ownerGraceUserId = model.ownerGrace?.userId,
        ownerGraceDeadline = model.ownerGrace?.deadline,
        status = model.status.name,
        currentSessionId = model.currentSessionId,
        emptySince = model.emptySince,
        timeControlMode = model.timeControl.mode.name,
        moveSeconds = model.timeControl.moveSeconds,
        gameSettings = model.gameSettings,
        createdAt = model.createdAt,
    )

    fun toModel(entity: RoomMemberEntity) = RoomMemberModel(
        roomId = entity.roomId,
        userId = entity.userId,
        role = RoomRole.valueOf(entity.role),
        joinedAt = entity.joinedAt,
        leftAt = entity.leftAt,
    )

    fun toEntity(model: RoomMemberModel) = RoomMemberEntity(
        roomId = model.roomId,
        userId = model.userId,
        role = model.role.name,
        joinedAt = model.joinedAt,
        leftAt = model.leftAt,
    )
}
