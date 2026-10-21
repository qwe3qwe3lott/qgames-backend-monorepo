package site.qgames.game.gatewayapi.dto.session

import io.swagger.v3.oas.annotations.media.Schema
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import java.time.Instant
import java.util.UUID

/** Неизменное описание партии, закреплённое при старте. */
data class SessionMetaDto(
    val game: SessionGameDto,
)

/** Игра партии: по [code] клиент выбирает схемы состояния и игровых событий. */
data class SessionGameDto(
    val code: GameCode,
    /** Правила (настройки) партии на момент старта: время и настройки игры. */
    val rules: RoomSettingsDto,
)

/** Игрок партии (модуль core). PUT по id: событие player-changed несёт игрока целиком. */
data class SessionPlayerDto(
    val id: UUID,
    /** Что игрок может сделать сейчас. Видит только сам игрок, остальным — пустой список. */
    val moveOptions: List<MoveOptionDto>,
)

/** Вариант хода: тот же конверт, что тело хода (MoveRequest). */
data class MoveOptionDto(
    /** Вид действия, например PASS. */
    val type: String,
    /** Параметры действия в свободном формате (формат задаёт игра); у PASS их нет. */
    val data: Map<String, Any?>? = null,
)

/** Очерёдность (модуль turns). */
data class SessionTurnsDto(
    /** Чей сейчас ход; null — ход ничей или партия закончилась. */
    val currentPlayerId: UUID?,
    /** Текущий раунд, с 1. */
    val round: Int,
    /** Сколько раундов в партии; null — не ограничено. */
    val rounds: Int?,
)

enum class SessionTimerStatus {
    RUNNING,
    PAUSED,

    /** Партия закончилась или отменена: deadline и pausedAt — null. */
    STOPPED,
}

/** Таймер хода (модуль timer). Пауза — состояние таймера. */
data class SessionTimerDto(
    val status: SessionTimerStatus,
    /** Когда истекает время текущего хода; null — ход ничей. На паузе не меняется: остаток — deadline − pausedAt. */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
)

/**
 * Состояние партии конкретной игры: блоки модулей (players, timer, turns) и поля игры. Игра — meta.game.code из
 * снапшота или события started; клиент сужает тип по нему.
 */
@Schema(
    description = "Состояние партии конкретной игры: блоки модулей (players, timer, turns) и поля игры. " +
        "Игра — meta.game.code снапшота или started",
    oneOf = [NothingSessionStateDto::class],
)
sealed interface SessionStateDto

/** Состояние партии «игры в ничто». */
data class NothingSessionStateDto(
    /** Игроки в порядке мест. */
    val players: List<SessionPlayerDto>,
    /** null — партия без таймера. */
    val timer: SessionTimerDto?,
    val turns: SessionTurnsDto,
    /** Сколько ходов сделано (включая пропущенные по времени). */
    val moves: Int,
) : SessionStateDto

/** Данные события moved конкретной игры (игра — meta.game.code). */
@Schema(
    description = "Данные события moved конкретной игры (игра — meta.game.code)",
    oneOf = [NothingMovedDto::class],
)
sealed interface SessionMovedDto

/** Ход в «игре в ничто». */
data class NothingMovedDto(
    val playerId: UUID,
    /** Вид хода; у «ничто» всегда PASS. */
    val type: String,
    /** Ход сделан автоматически: время хода истекло. */
    val timeout: Boolean,
) : SessionMovedDto
