package site.qgames.game.gatewayapi.dto.room

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/** [code] — код игры в путях API (`/api/games/{gameCode}/…`). */
enum class GameType(val code: String) {
    NOTHING(GameCodes.NOTHING),
    ;

    companion object {
        fun fromCode(code: String): GameType? = entries.firstOrNull { it.code == code }
    }
}

/** Коды игр строками: нужны в аннотациях OpenAPI. */
object GameCodes {
    const val NOTHING = "nothing"
}

enum class RoomStatus {
    /** Партия не идёт: можно менять роли и стартовать. */
    WAITING,

    /** Идёт партия. */
    IN_GAME,
}

enum class RoomRole {
    PLAYER,
    SPECTATOR,
}

enum class Avatar {
    CAT,
    DOG,
}

enum class SessionStatus {
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}

data class CreateRoomRequest(
    @field:NotNull
    val gameType: GameType,
)

data class ChangeRoleRequest(
    @field:NotNull
    val role: RoomRole,
)

data class MoveRequest(
    /** Вид действия, например `PASS` или `PLACE_TILE`. Набор видов задаёт игра. */
    @field:NotBlank
    val type: String,
    /** Параметры действия в свободном формате, их разбирает правило игры. У `PASS` их нет. */
    val data: Map<String, Any?>? = null,
    /** Версия партии, которую видел клиент. Если партия уже изменилась — 409, чтобы не ходить по устаревшему состоянию. */
    val expectedVersion: Long? = null,
)

data class RoomResponse(
    val id: UUID,
    val gameType: GameType,
    val status: RoomStatus,
    /** Нет, если в комнате никого не осталось: владельцем станет первый вошедший. */
    val ownerId: UUID?,
    val minPlayers: Int,
    val maxPlayers: Int,
    val members: List<RoomMemberResponse>,
    /** Текущая партия, а если её нет — последняя завершённая или отменённая. */
    val session: SessionResponse?,
    val createdAt: Instant,
)

data class RoomMemberResponse(
    val userId: UUID,
    /** null, пока профиль пользователя не реплицировался из user-auth. */
    val nickname: String?,
    val avatar: Avatar?,
    val role: RoomRole,
    val owner: Boolean,
    val joinedAt: Instant,
)

data class SessionResponse(
    val id: UUID,
    val status: SessionStatus,
    /** Игроки партии в порядке мест. Место сохраняется, даже если игрок вышел из комнаты. */
    val players: List<UUID>,
    /** Растёт с каждым ходом; передаётся в MoveRequest.expectedVersion. */
    val version: Long,
    /** Состояние партии с точки зрения запросившего пользователя, формат задаёт игра. */
    val state: Map<String, Any?>,
)
