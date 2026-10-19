package site.qgames.game.entity.room

import java.time.Instant
import java.util.UUID

/** Строка таблицы room. Перечисления хранятся строками, в модель их переводит RoomEntityMapper. */
data class RoomEntity(
    val id: UUID,
    val gameType: String,
    val ownerId: UUID?,
    val status: String,
    val currentSessionId: UUID?,
    val emptySince: Instant?,
    val createdAt: Instant,
)

/** Строка таблицы room_member. */
data class RoomMemberEntity(
    val roomId: UUID,
    val userId: UUID,
    val role: String,
    val joinedAt: Instant,
)
