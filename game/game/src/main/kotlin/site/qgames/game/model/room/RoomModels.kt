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
    /** Когда вышедший участник перестанет числиться в комнате; null — участник присутствует. */
    val leaveDeadline: Instant? = null,
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

/** Режим контроля времени. Имя значения совпадает с TimeControlMode из SPI игр. */
enum class RoomTimeControlMode {
    NONE,
    PER_MOVE,
}
