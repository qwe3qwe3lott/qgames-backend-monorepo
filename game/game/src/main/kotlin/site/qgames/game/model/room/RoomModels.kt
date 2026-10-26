package site.qgames.game.model.room

import site.qgames.game.model.game.GameCode
import java.time.Instant
import java.util.UUID

data class RoomModel(
    val id: UUID,
    /** Игра комнаты. */
    val gameCode: GameCode,
    /**
     * Нет только в комнате без присутствующих: владельцем станет первый вошедший. Владелец всегда присутствует в
     * комнате.
     */
    val ownerId: UUID?,
    val status: RoomStatus,
    val currentSessionId: UUID?,
    /** С какого момента в комнате нет присутствующих участников (отсутствующие игроки партии комнату не держат). */
    val emptySince: Instant?,
    /** Шахматные часы следующей партии; null — без таймера. */
    val timeControl: RoomTimeControlModel?,
    /** JSON настроек игры, формат задаёт модуль игры (GameRules.settingsClass). */
    val gameSettings: String,
    val createdAt: Instant,
)

data class RoomMemberModel(
    val roomId: UUID,
    val userId: UUID,
    val role: RoomRole,
    val joinedAt: Instant,
    /**
     * Когда игрок идущей партии закрыл последний поток комнаты; null — участник присутствует. Остальные участники
     * при уходе удаляются сразу, поэтому отсутствующим бывает только игрок идущей партии.
     */
    val leftAt: Instant? = null,
)

/** Шахматные часы: запас на партию и добавка за ход в секундах (как TimeControl из SPI игр). */
data class RoomTimeControlModel(
    val baseSeconds: Int,
    val incrementSeconds: Int,
)

/**
 * Настройки комнаты целиком: часы (null — без таймера) и [gameSettings] — объект настроек модуля игры
 * (GameRules.settingsClass).
 */
data class RoomSettingsModel(
    val timeControl: RoomTimeControlModel?,
    val gameSettings: Any,
)

enum class RoomStatus {
    WAITING,
    IN_GAME,
}

enum class RoomRole {
    PLAYER,
    SPECTATOR,
}
