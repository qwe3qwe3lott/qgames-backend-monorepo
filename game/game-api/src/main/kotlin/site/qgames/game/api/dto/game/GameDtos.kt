package site.qgames.game.api.dto.game

import com.fasterxml.jackson.annotation.JsonValue
import site.qgames.game.api.dto.room.GameSettingsLimitsDto
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.RoomSettingsDto

/** Код игры. В JSON и в путях API (`/api/games/{gameCode}/…`) — строчный [code], например `nothing`. */
enum class GameCode(@get:JsonValue val code: String) {
    /** «Игра в ничто»: тестовая игра, игроки по очереди делают ход PASS. */
    NOTHING(GameCodes.NOTHING),

    /** Каркассон. */
    CARCASSONNE(GameCodes.CARCASSONNE),

    /** Шашки: вариант правил — настройка комнаты. */
    CHECKERS(GameCodes.CHECKERS),
    ;

    companion object {
        fun fromCode(code: String): GameCode? = entries.firstOrNull { it.code == code }
    }
}

/** Коды игр строками: нужны в аннотациях Jackson и OpenAPI (путь, дискриминатор gameCode). */
object GameCodes {
    const val NOTHING = "nothing"
    const val CARCASSONNE = "carcassonne"
    const val CHECKERS = "checkers"
}

/** Каталог игр в порядке показа, без скрытых (HIDDEN) и неопубликованных (NOT_PUBLISHED). */
data class GameListResponse(
    val games: List<GameSummaryDto>,
)

/** Игра в каталоге. */
data class GameSummaryDto(
    val code: GameCode,
    val title: String,
    val description: String,
)

/** Игра целиком: описание из каталога и параметры правил для формы настроек комнаты. */
data class GameDetailsResponse(
    val code: GameCode,
    val title: String,
    val description: String,
    val rules: GameRulesDto,
)

/** Параметры правил игры: по ним строится форма настроек комнаты. */
data class GameRulesDto(
    val minPlayers: Int,
    val maxPlayers: Int,
    /** Границы лимита наблюдателей (RoomOptionsDto.maxSpectators), одинаковые у всех игр. */
    val spectators: IntRangeDto,
    /** Границы часов advanced-настроек. */
    val timeControl: TimeControlLimitsDto,
    val gameSettingsLimits: GameSettingsLimitsDto,
    /** Настройки, с которыми создаётся комната (simple-режим). */
    val defaultSettings: RoomSettingsDto,
)

/** Какие часы разрешает игра в advanced-настройках. */
data class TimeControlLimitsDto(
    /** Можно ли играть без таймера (TimeControlDto.enabled = false). */
    val optional: Boolean,
    /** Границы запаса на партию в секундах. */
    val baseSeconds: IntRangeDto,
    /** Границы добавочного времени (инкремента или задержки) в секундах. */
    val bonusSeconds: IntRangeDto,
)
