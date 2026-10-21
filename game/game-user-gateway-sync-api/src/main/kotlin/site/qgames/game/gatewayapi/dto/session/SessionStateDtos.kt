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

/** Игра партии: по [code] клиент выбирает схему состояния. */
data class SessionGameDto(
    val code: GameCode,
    /** Правила (настройки) партии на момент старта: время и настройки игры. */
    val rules: RoomSettingsDto,
)

/** Игрок партии (модуль core): элемент массива `state.players`, адресуется по id. */
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
 * Состояние партии конкретной игры: блоки модулей (players, timer, turns) и поля игры. Игра — meta.game.code
 * снапшота; клиент сужает тип по нему и проверяет этой схемой состояние после применения каждого события.
 */
@Schema(
    description = "Состояние партии конкретной игры: блоки модулей (players, timer, turns) и поля игры. " +
        "Игра — meta.game.code снапшота; этой схемой клиент проверяет состояние после каждого события",
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
    /** Сколько ходов сделали игроки; пропуски по истечении времени не считаются. */
    val moves: Int,
) : SessionStateDto
