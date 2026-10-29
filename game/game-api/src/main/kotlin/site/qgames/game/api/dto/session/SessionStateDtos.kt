package site.qgames.game.api.dto.session

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.GameAdvancedSettingsDto
import java.time.Instant
import java.util.UUID

/** Неизменное описание партии, закреплённое при старте. */
data class SessionMetaDto(
    val game: SessionGameDto,
    /**
     * Итоговые правила партии для подсказок ввода (схема — по игре из game.code), выведенные из advanced-настроек;
     * null — у игры их нет (например, «игра в ничто»). Записываются при старте партии и не меняются.
     */
    val advancedRules: SessionAdvancedRulesDto?,
)

/**
 * Итоговые правила конкретной игры: по этим флагам клиент подсказывает ввод хода, не зная правил игры. Игра —
 * meta.game.code снапшота; окончательно ход проверяет сервер.
 */
@Schema(
    description = "Итоговые правила конкретной игры для подсказок ввода (игра — meta.game.code); ход окончательно " +
        "проверяет сервер",
    oneOf = [CheckersAdvancedRulesDto::class],
)
sealed interface SessionAdvancedRulesDto

/** Исход партии. */
enum class SessionResultOutcome {
    /** Победа: победители — winnerIds. */
    WIN,

    /** Ничья: winnerIds пуст. */
    DRAW,
}

/** Итог партии: ставится вместе с концом партии по правилам; у идущей и отменённой партии — null. */
data class SessionResultDto(
    val outcome: SessionResultOutcome,
    /** Победители; у ничьей — пустой список. */
    val winnerIds: List<UUID>,
    /**
     * Машиночитаемая причина окончания, набор задаёт игра (шашки: NO_PIECES, NO_MOVES, RESIGN, TIMEOUT, DRAW_RULE,
     * DRAW_AGREED, REPETITION, ENDGAME_RULE).
     */
    val reason: String,
)

/** Игра партии: по [code] клиент выбирает схему состояния. */
data class SessionGameDto(
    val code: GameCode,
    /** Advanced-настройки партии на момент старта: по ним идёт партия. */
    val settings: GameAdvancedSettingsDto,
)

/** Игрок партии (поля core): элемент массива `state.players`, адресуется по id. */
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

/** Очерёдность. */
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

    /** Партия закончилась или отменена: deadline и pausedAt — null, clocks — итоговые остатки. */
    STOPPED,
}

/** Шахматные часы партии. Пауза — состояние таймера. */
data class SessionTimerDto(
    val status: SessionTimerStatus,
    /**
     * Когда кончится запас того, чей ход; null — ход ничей. На паузе не меняется: остаток — deadline − pausedAt.
     */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
    /** Запас каждого игрока на момент последней смены хода; у ходящего текущий остаток — по deadline. */
    val clocks: List<PlayerClockDto>,
)

/** Запас времени игрока. */
data class PlayerClockDto(
    val playerId: UUID,
    /** Остаток в миллисекундах. */
    val remainingMs: Long,
)

/**
 * Состояние партии конкретной игры: блоки core (players, timer, turns, result) и поля игры. Игра — meta.game.code
 * снапшота; клиент сужает тип по нему и проверяет этой схемой состояние после применения каждого события.
 */
@Schema(
    description = "Состояние партии конкретной игры: блоки core (players, timer, turns, result) и поля игры. " +
        "Игра — meta.game.code снапшота; этой схемой клиент проверяет состояние после каждого события",
    oneOf = [NothingSessionStateDto::class, CheckersSessionStateDto::class],
)
sealed interface SessionStateDto

/** Состояние партии «игры в ничто». */
data class NothingSessionStateDto(
    /** Игроки в порядке мест. */
    val players: List<SessionPlayerDto>,
    /** null — партия без таймера. */
    val timer: SessionTimerDto?,
    val turns: SessionTurnsDto,
    /** Итог партии; у «игры в ничто» не ставится — всегда null. */
    val result: SessionResultDto?,
    /** Сколько ходов сделали игроки; пропуски по истечении времени не считаются. */
    val moves: Int,
) : SessionStateDto

/** Цвет шашек. */
enum class CheckersColor {
    WHITE,
    BLACK,
}

/** Вид шашки. */
enum class CheckersPieceKind {
    /** Простая. */
    MAN,

    /** Дамка. */
    KING,
}

/** Итоговые правила партии шашек: доска, первый ход и флаги — из advanced-настроек. */
data class CheckersAdvancedRulesDto(
    /** Размер доски. */
    val boardSize: Int,
    /** Кто ходит первым. */
    val firstColor: CheckersColor,
    /** Тихий ход простой назад. */
    val menMoveBackward: Boolean,
    /** Взятие простой назад. */
    val menCaptureBackward: Boolean,
    /** Дамка ходит и бьёт на любое число клеток. */
    val flyingKings: Boolean,
    /** Взятие обязательно. */
    val captureRequired: Boolean,
    /** Брать только ветку с наибольшим числом сбитых. */
    val maxCaptureRequired: Boolean,
    /** Ставшая дамкой посреди взятия бьёт дальше как дамка. */
    val promoteMidCapture: Boolean,
    /** Турецкий удар: сбитые снимаются после хода, через сбитую шашку второй раз не прыгать. */
    val removeCapturedAfterMove: Boolean,
) : SessionAdvancedRulesDto

/** Игрок партии шашек: поля core и цвет. */
data class CheckersPlayerDto(
    val id: UUID,
    /** Что игрок может сделать сейчас (MOVE, RESIGN). Видит только сам игрок, остальным — пустой список. */
    val moveOptions: List<MoveOptionDto>,
    val color: CheckersColor,
)

/** Шашка на доске: элемент массива state.pieces, адресуется по id. */
data class CheckersPieceDto(
    /** w1…w12 у белых, b1…b12 у чёрных (для доски 8×8). */
    val id: String,
    val color: CheckersColor,
    val kind: CheckersPieceKind,
    /** Клетка [x, y], отсчёт с 1; [1, 1] — тёмная клетка в углу белых, белые начинают на нижних рядах. */
    @field:ArraySchema(schema = Schema(type = "integer", format = "int32"), minItems = 2, maxItems = 2)
    val square: List<Int>,
)

/** Последний ход партии шашек. */
data class CheckersLastMoveDto(
    val pieceId: String,
    /** Полный путь шашки, включая начальную клетку; клетки — [x, y]. */
    val path: List<List<Int>>,
    /** Клетки сбитых шашек в порядке взятия; клетки — [x, y]. */
    val captured: List<List<Int>>,
)

/** Состояние партии шашек. */
data class CheckersSessionStateDto(
    /** Игроки в порядке мест, с цветом. */
    val players: List<CheckersPlayerDto>,
    /** null — партия без таймера. */
    val timer: SessionTimerDto?,
    val turns: SessionTurnsDto,
    /** Итог партии; null — партия идёт или отменена. */
    val result: SessionResultDto?,
    /** Шашки на доске; сбитые удаляются. */
    val pieces: List<CheckersPieceDto>,
    /** Последний ход; null — ходов ещё не было. */
    val lastMove: CheckersLastMoveDto?,
    /** Сколько ходов подряд (обеих сторон в сумме) сделано только дамками без взятий (ничья DRAW_RULE). */
    @field:Schema(
        description = "Сколько ходов подряд (обеих сторон в сумме) сделано только дамками без взятий; на пороге варианта " +
            "(русские 30, международные 50, английские 80) партия заканчивается ничьей (DRAW_RULE)",
    )
    val quietMoves: Int,
    /** Сколько ходов (обеих сторон) прошло с последнего взятия или превращения — для правил окончаний (ENDGAME_RULE). */
    val materialMoves: Int,
    /** Служебное: ключи позиций с последнего взятия или хода простой — для троекратного повторения (REPETITION). */
    val positionHistory: List<String>,
    /** Кто предложил ничью вместе со своим ходом; null — предложения нет. Принять — ходом ACCEPT_DRAW. */
    val drawOfferedBy: UUID?,
) : SessionStateDto

/**
 * Параметры хода MOVE в шашках (`data` тела хода MoveRequest): полный путь шашки, включая начальную клетку. Принять
 * ничью — `{type: "ACCEPT_DRAW"}`, сдача — `{type: "RESIGN"}`, оба без data.
 */
data class CheckersMoveDataDto(
    /** Клетки пути [x, y] с начальной, например [[3, 3], [5, 5], [7, 3]]. */
    val path: List<List<Int>>,
    /** Предложить ничью вместе с этим ходом; по умолчанию false. */
    val offerDraw: Boolean?,
)

/** Схемы параметров ходов игр (`data` тела хода MoveRequest): публикуются в components OpenAPI. */
object MoveDataSchemas {
    val CLASSES: List<Class<*>> = listOf(CheckersMoveDataDto::class.java)
}
