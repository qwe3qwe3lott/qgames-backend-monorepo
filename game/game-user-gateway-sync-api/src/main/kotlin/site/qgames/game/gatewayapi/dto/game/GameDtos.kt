package site.qgames.game.gatewayapi.dto.game

import com.fasterxml.jackson.annotation.JsonValue
import site.qgames.game.gatewayapi.dto.room.GameSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode

/** Код игры. В JSON и в путях API (`/api/games/{gameCode}/…`) — строчный [code], например `nothing`. */
enum class GameCode(@get:JsonValue val code: String) {
    /** «Игра в ничто»: тестовая игра, игроки по очереди делают ход PASS. */
    NOTHING(GameCodes.NOTHING),

    /** Каркассон. */
    CARCASSONNE(GameCodes.CARCASSONNE),
    ;

    companion object {
        fun fromCode(code: String): GameCode? = entries.firstOrNull { it.code == code }
    }
}

/** Коды игр строками: нужны в аннотациях Jackson и OpenAPI (путь, дискриминатор gameCode). */
object GameCodes {
    const val NOTHING = "nothing"
    const val CARCASSONNE = "carcassonne"
}

/**
 * Статус публикации игры. Неопубликованные (NOT_PUBLISHED) и принудительно отключённые (FORCE_DISABLED) игры в
 * ответы не попадают.
 */
enum class GamePublishStatus {
    /** Доступна: можно создавать комнаты и начинать партии. */
    PUBLISHED,

    /** Временно недоступна: новые комнаты и партии запрещены, идущие партии продолжаются. */
    DISABLED,

    /** Скрыта из списка, но доступна по прямой ссылке. */
    HIDDEN,
}

/** Каталог игр: без скрытых, в порядке показа. */
data class GameListResponse(
    val games: List<GameSummaryDto>,
)

/** Игра в каталоге. */
data class GameSummaryDto(
    val code: GameCode,
    val title: String,
    val description: String,
    val publishStatus: GamePublishStatus,
)

/** Игра целиком: описание из каталога и параметры правил для формы настроек комнаты. */
data class GameDetailsResponse(
    val code: GameCode,
    val title: String,
    val description: String,
    val publishStatus: GamePublishStatus,
    val rules: GameRulesDto,
)

/** Параметры правил игры для формы настроек комнаты. */
data class GameRulesDto(
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeControl: TimeControlLimitsDto,
    val gameSettingsLimits: GameSettingsLimitsDto,
    /** Настройки, которыми заполняется форма создания комнаты. */
    val defaultSettings: RoomSettingsDto,
)

/** Какие правила времени разрешает игра. */
data class TimeControlLimitsDto(
    val modes: List<TimeControlMode>,
    /** Границы времени на ход в секундах (для PER_MOVE). */
    val moveSeconds: IntRangeDto,
)
