package site.qgames.game.model.room

import java.time.Instant
import java.util.UUID

data class RoomModel(
    val id: UUID,
    val gameType: RoomGameType,
    /** Нет, если в комнате никого не осталось: владельцем станет первый вошедший. */
    val ownerId: UUID?,
    val status: RoomStatus,
    val currentSessionId: UUID?,
    /** С какого момента в комнате нет ни игроков, ни наблюдателей. */
    val emptySince: Instant?,
    val createdAt: Instant,
)

data class RoomMemberModel(
    val roomId: UUID,
    val userId: UUID,
    val role: RoomRole,
    val joinedAt: Instant,
)

/** Тип игры комнаты. Имя значения совпадает с GameRules.type модуля игры. */
enum class RoomGameType {
    NOTHING,
}

enum class RoomStatus {
    WAITING,
    IN_GAME,
}

enum class RoomRole {
    PLAYER,
    SPECTATOR,
}
