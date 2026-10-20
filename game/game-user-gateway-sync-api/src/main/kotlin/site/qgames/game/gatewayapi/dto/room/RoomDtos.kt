package site.qgames.game.gatewayapi.dto.room

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import site.qgames.game.gatewayapi.dto.game.GameCode
import java.time.Instant
import java.util.UUID

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

/** Игра — из кода игры в пути. */
data class CreateRoomRequest(
    @field:NotNull
    @field:Valid
    val settings: RoomSettingsDto,
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
    val gameCode: GameCode,
    val status: RoomStatus,
    /** Нет, если в комнате никого не осталось: владельцем станет первый вошедший. */
    val ownerId: UUID?,
    val minPlayers: Int,
    val maxPlayers: Int,
    val members: List<RoomMemberResponse>,
    /** Текущая партия, а если её нет — последняя завершённая или отменённая. */
    val session: SessionResponse?,
    val createdAt: Instant,
    /** Текущие настройки комнаты: новая партия стартует с ними. */
    val settings: RoomSettingsDto,
    /** Время сервера на момент ответа: по нему клиент поправляет свои часы для обратного отсчёта хода. */
    val serverTime: Instant,
)

data class RoomMemberResponse(
    val userId: UUID,
    /** null в первые секунды после регистрации, пока профиль не дошёл до игрового сервиса. */
    val nickname: String?,
    val avatar: Avatar?,
    val role: RoomRole,
    val owner: Boolean,
    /**
     * Участник вышел (закрыл последний поток событий комнаты), но ещё числится в комнате, пока не истёк срок выхода:
     * сохраняет роль, место и владение. Новое подключение к потоку в пределах срока отменяет выход.
     */
    val leaving: Boolean,
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
    /** Когда истекает время текущего хода. null — партия без таймера или уже закончена/отменена. */
    val moveDeadline: Instant?,
    /**
     * С какого момента партия на паузе; null — не на паузе. На паузе moveDeadline не меняется: остаток времени
     * хода — moveDeadline − pausedAt.
     */
    val pausedAt: Instant?,
)
