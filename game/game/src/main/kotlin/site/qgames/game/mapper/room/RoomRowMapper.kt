package site.qgames.game.mapper.room

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.user.UserAvatar
import site.qgames.game.model.user.UserProfileModel
import site.qgames.game.table.room.RoomMemberTable
import site.qgames.game.table.room.RoomTable
import site.qgames.userauth.api.table.user.UserProfileTable

/** Строки таблиц `room`, `room_member` и профиля `user` (user-auth) → модели. */
@Component
class RoomRowMapper(private val roomSettingsJsonMapper: RoomSettingsJsonMapper) {

    fun toRoom(row: ResultRow): RoomModel {
        val gameCode = row[RoomTable.gameCode]
        return RoomModel(
            id = row[RoomTable.id],
            gameCode = GameCode.fromCode(gameCode) ?: error("Unknown game code '$gameCode'"),
            ownerId = row[RoomTable.ownerId],
            status = RoomStatus.valueOf(row[RoomTable.status]),
            currentSessionId = row[RoomTable.currentSessionId],
            emptySince = row[RoomTable.emptySince],
            options = roomSettingsJsonMapper.options(row[RoomTable.settings]),
            simpleSettings = roomSettingsJsonMapper.simple(row[RoomTable.settings]),
            advancedSettings = roomSettingsJsonMapper.advanced(row[RoomTable.settings]),
            createdAt = row[RoomTable.createdAt],
        )
    }

    fun toMember(row: ResultRow) = RoomMemberModel(
        roomId = row[RoomMemberTable.roomId],
        userId = row[RoomMemberTable.userId],
        role = RoomRole.valueOf(row[RoomMemberTable.role]),
        joinedAt = row[RoomMemberTable.joinedAt],
        leftAt = row[RoomMemberTable.leftAt],
    )

    /** Профиль из левого соединения с `user`: null, если строки пользователя нет. */
    fun toProfile(row: ResultRow): UserProfileModel? {
        val id = row.getOrNull(UserProfileTable.id) ?: return null
        return UserProfileModel(
            id = id,
            nickname = row[UserProfileTable.nickname],
            avatar = UserAvatar.valueOf(row[UserProfileTable.avatar]),
        )
    }
}
