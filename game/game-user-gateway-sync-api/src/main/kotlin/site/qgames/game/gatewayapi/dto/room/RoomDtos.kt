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
)

data class RoomResponse(
    val id: UUID,
    val gameCode: GameCode,
    val status: RoomStatus,
    /**
     * Нет в окне владения (владелец ушёл, 15 с ещё не прошли: команды владельца не может выполнить никто), в ответе
     * создания комнаты (создатель ещё не подключился) и в комнате без присутствующих (владельцем станет первый
     * вошедший).
     */
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
     * Игрок идущей партии не подключён (закрыл последний поток событий комнаты). Он закреплён до конца партии и
     * сохраняет место, подключение снимает признак; конец или отмена партии удаляют отсутствующих. Остальные участники
     * при уходе удаляются сразу, поэтому у них признака не бывает.
     */
    val leaving: Boolean,
    val joinedAt: Instant,
)

/**
 * Партия комнаты кратко. Состояние партии приходит только из её потока
 * (`GET /api/games/{gameCode}/rooms/{roomId}/sessions/{id}/events`).
 */
data class SessionResponse(
    val id: UUID,
    val status: SessionStatus,
)
