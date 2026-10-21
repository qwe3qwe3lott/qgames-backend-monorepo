package site.qgames.game.model.room

import site.qgames.game.model.game.GameCode
import java.time.Instant
import java.util.UUID

data class RoomModel(
    val id: UUID,
    /** Игра комнаты. */
    val gameCode: GameCode,
    /**
     * Нет во время окна владения ([ownerGrace]) и в комнате без присутствующих после окна (тогда владельцем станет
     * первый вошедший). Владелец всегда присутствует в комнате.
     */
    val ownerId: UUID?,
    /** Окно владения: прежний владелец ушёл и может вернуть владение до срока. Есть только при [ownerId] = null. */
    val ownerGrace: RoomOwnerGraceModel?,
    val status: RoomStatus,
    val currentSessionId: UUID?,
    /** С какого момента в комнате нет присутствующих участников (отсутствующие игроки партии комнату не держат). */
    val emptySince: Instant?,
    /** Правила времени следующей партии. */
    val timeControl: RoomTimeControlModel,
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

/** Окно владения: до [deadline] вернуть владение может только ушедший владелец [userId]. */
data class RoomOwnerGraceModel(
    val userId: UUID,
    val deadline: Instant,
)

/** Правила времени: режим и секунды на ход (только для [RoomTimeControlMode.PER_MOVE]). */
data class RoomTimeControlModel(
    val mode: RoomTimeControlMode,
    val moveSeconds: Int?,
)

/** Настройки комнаты целиком; [gameSettings] — объект настроек модуля игры (GameRules.settingsClass). */
data class RoomSettingsModel(
    val timeControl: RoomTimeControlModel,
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

/** Режим контроля времени. Имя значения совпадает с TimeControlMode из SPI игр. */
enum class RoomTimeControlMode {
    NONE,
    PER_MOVE,
}
