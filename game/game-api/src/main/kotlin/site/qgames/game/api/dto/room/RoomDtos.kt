package site.qgames.game.api.dto.room

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import site.qgames.game.api.dto.game.GameCode
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

data class ChangeRoleRequest(
    @field:NotNull
    val role: RoomRole,
)

data class TransferOwnershipRequest(
    /** Новый владелец — присутствующий участник комнаты. */
    @field:NotNull
    val userId: UUID,
)

data class MoveRequest(
    /** Вид действия, например `PASS` или `MOVE`. Набор видов задаёт игра. */
    @field:NotBlank
    val type: String,
    /** Параметры действия в формате игры, их разбирает правило игры. У `PASS` и `RESIGN` их нет. */
    @field:Schema(
        description = "Параметры действия в формате игры; у PASS и RESIGN их нет. Схемы по играм — в components: " +
            "шашки, MOVE — CheckersMoveDataDto",
    )
    val data: Map<String, Any?>? = null,
)

data class RoomResponse(
    val id: UUID,
    val gameCode: GameCode,
    val status: RoomStatus,
    /**
     * Нет в ответе создания комнаты (создатель ещё не подключился) и в комнате без присутствующих (владельцем станет
     * первый вошедший).
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

/** Участник комнаты. Владелец — RoomResponse.ownerId. */
data class RoomMemberResponse(
    /** Id пользователя: по нему участник адресуется в событиях потока комнаты (`["members", {id}]`). */
    val id: UUID,
    /** null в первые секунды после регистрации, пока профиль не дошёл до игрового сервиса. */
    val nickname: String?,
    val avatar: Avatar?,
    val role: RoomRole,
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
