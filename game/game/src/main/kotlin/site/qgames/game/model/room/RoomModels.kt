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
    /** Настройки самой комнаты. */
    val options: RoomOptionsModel,
    /** JSON simple-настроек игры (GameRules.simpleClass); null — комната в advanced-режиме. */
    val simpleSettings: String?,
    /**
     * JSON advanced-настроек игры (GameRules.advancedClass), с ними стартует следующая партия. В simple-режиме выведен
     * из [simpleSettings].
     */
    val advancedSettings: String,
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

/** Настройки самой комнаты (группа room): меняются в любой момент. */
data class RoomOptionsModel(
    /** Сколько наблюдателей может войти; при понижении вошедшие остаются. */
    val maxSpectators: Int,
) {
    companion object {
        /** Границы лимита наблюдателей, одинаковые у всех игр. */
        val MAX_SPECTATORS = 0..12

        val DEFAULT = RoomOptionsModel(maxSpectators = 8)
    }
}

/**
 * Настройки комнаты целиком: [room], [simple] — объект simple-настроек модуля игры (GameRules.simpleClass; null —
 * advanced-режим) и [advanced] — объект advanced-настроек (GameRules.advancedClass).
 */
data class RoomSettingsModel(
    val room: RoomOptionsModel,
    val simple: Any?,
    val advanced: Any,
)

/**
 * Замена групп настроек: переданная группа заменяется целиком, null — не меняется. [simple] и [advanced] —
 * объекты настроек модуля игры, взаимоисключающие.
 */
data class RoomSettingsUpdateModel(
    val room: RoomOptionsModel?,
    val simple: Any?,
    val advanced: Any?,
)

enum class RoomStatus {
    WAITING,
    IN_GAME,
}

enum class RoomRole {
    PLAYER,
    SPECTATOR,
}
