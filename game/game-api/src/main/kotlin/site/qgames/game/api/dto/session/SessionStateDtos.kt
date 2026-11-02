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

/** Вид часов стадии партии. */
enum class SessionTimerKind {
    /** Шахматные часы игроков: запас у каждого (clocks), deadline — у ходящего. */
    PLAYERS,

    /** Общий отсчёт стадии, где ходят сразу несколько игроков: один deadline на всех, clocks пуст. */
    STAGE,
}

/** Часы текущей стадии партии. Пауза — состояние таймера. */
data class SessionTimerDto(
    val kind: SessionTimerKind,
    val status: SessionTimerStatus,
    /**
     * Когда кончится время: у часов игроков (PLAYERS) — запас того, чей ход (null — ход ничей), у отсчёта стадии
     * (STAGE) — время стадии. На паузе не меняется: остаток — deadline − pausedAt.
     */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
    /** Запас каждого игрока на момент последней смены хода; у ходящего текущий остаток — по deadline. У STAGE пуст. */
    val clocks: List<PlayerClockDto>,
)

/** Запас времени игрока. */
data class PlayerClockDto(
    val playerId: UUID,
    /** Остаток в миллисекундах. */
    val remainingMs: Long,
)

/**
 * Состояние текущей стадии партии конкретной игры: блоки core (stage, players, timer, turns, result) и поля игры. Игра —
 * meta.game.code снапшота, стадия — state.stage; клиент сужает тип по ним и проверяет этой схемой состояние после
 * применения каждого события.
 */
@Schema(
    description = "Состояние текущей стадии партии конкретной игры: блоки core (stage, players, timer, turns, result) и " +
        "поля игры. Игра — meta.game.code снапшота, стадия — state.stage; этой схемой клиент проверяет состояние после " +
        "каждого события. Смена стадии (stage-changed) заменяет состояние целиком",
    oneOf = [
        NothingSessionStateDto::class,
        CheckersSessionStateDto::class,
        CheckersColorPickStateDto::class,
        CroissantSessionStateDto::class,
    ],
)
sealed interface SessionStateDto

/** Состояние партии «игры в ничто». */
data class NothingSessionStateDto(
    /** Стадия: у «игры в ничто» одна — play. */
    @field:Schema(type = "string", allowableValues = [STAGE_PLAY])
    val stage: String,
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

/** Состояние стадии выбора цвета шашек (FIRST_PICK): обоим игрокам доступен PICK_COLOR, очереди нет. */
data class CheckersColorPickStateDto(
    @field:Schema(type = "string", allowableValues = [CHECKERS_STAGE_COLOR_PICK])
    val stage: String,
    /** Игроки в порядке мест, цвета ещё нет. */
    val players: List<SessionPlayerDto>,
    /** Общий отсчёт стадии (kind = STAGE). */
    val timer: SessionTimerDto?,
    /** Очередь ничья: currentPlayerId = null. */
    val turns: SessionTurnsDto,
    /** Итога нет: null. */
    val result: SessionResultDto?,
) : SessionStateDto

/** Состояние стадии игры шашек. */
data class CheckersSessionStateDto(
    @field:Schema(type = "string", allowableValues = [STAGE_PLAY])
    val stage: String,
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
 * ничью — `{type: "ACCEPT_DRAW"}`, сдача — `{type: "RESIGN"}`, оба без data. Выбор цвета — PICK_COLOR
 * (CheckersPickColorDataDto).
 */
data class CheckersMoveDataDto(
    /** Клетки пути [x, y] с начальной, например [[3, 3], [5, 5], [7, 3]]. */
    val path: List<List<Int>>,
    /** Предложить ничью вместе с этим ходом; по умолчанию false. */
    val offerDraw: Boolean?,
)

/**
 * Параметры хода PICK_COLOR в шашках (стадия выбора цвета): свой цвет. Кто выбрал первым, получает его, соперник —
 * противоположный; опоздавший выбор — 422 MOVE_REJECTED с reason COLOR_ALREADY_PICKED.
 */
data class CheckersPickColorDataDto(
    val color: CheckersColor,
)

/** Схемы параметров ходов игр (`data` тела хода MoveRequest): публикуются в components OpenAPI. */
object MoveDataSchemas {
    val CLASSES: List<Class<*>> = listOf(
        CheckersMoveDataDto::class.java,
        CheckersPickColorDataDto::class.java,
        CroissantMoveDataDto::class.java,
    )
}

/** Основная стадия партии (у игр из одной стадии — единственная). */
const val STAGE_PLAY = "play"

/** Стадия выбора цвета шашек. */
const val CHECKERS_STAGE_COLOR_PICK = "color-pick"

/** Сторона тайла «Круассана»; в JSON — имя значения. */
enum class CroissantDirection {
    N,
    E,
    S,
    W,
}

/** Чем занята сторона тайла: пекарня, мучная тропа или луг. */
enum class CroissantEdge {
    BAKERY,
    TRAIL,
    PASTURE,
}

/** Тип участка сети. */
enum class CroissantSpotType {
    /** Пекарня: квартал с вывеской. */
    BAKERY,

    /** Мучная тропа: путь доставки. */
    TRAIL,

    /** Сыроварня при аббатстве. */
    DAIRY,

    /** Луг: стада пасутся, итоговый подсчёт только в конце. */
    PASTURE,
}

/** Полуребро: сторона и её половина (0 — первая по часовой стрелке). */
data class CroissantHalfEdgeDto(
    val dir: CroissantDirection,
    val half: Int,
)

/** Пекарня на тайле: стороны квартала и вывеска. */
data class CroissantBakeryDto(
    val sides: List<CroissantDirection>,
    val sign: Boolean,
)

/** Мучная тропа на тайле: стороны, на которые она выходит. */
data class CroissantTrailDto(
    val sides: List<CroissantDirection>,
)

/** Луг на тайле: полуребра выхода и индексы примыкающих пекарен тайла. */
data class CroissantPastureDto(
    val halves: List<CroissantHalfEdgeDto>,
    val bakeries: List<Int>,
)

/**
 * Тип тайла «Круассана»: геометрия участков в базовой ориентации. Каталог лежит в состоянии партии, чтобы клиент рисовал
 * тайлы по данным сервера и не дублировал их у себя.
 */
data class CroissantTileDto(
    val code: String,
    /** Подпись тайла для интерфейса. */
    val title: String,
    /** Чем занята каждая сторона: N, E, S, W. */
    val edges: List<CroissantEdge>,
    val bakeries: List<CroissantBakeryDto>,
    val trails: List<CroissantTrailDto>,
    /** Сыроварня при аббатстве на этом тайле. */
    val dairy: Boolean,
    val pastures: List<CroissantPastureDto>,
)

/** Выложенный тайл: id — `x:y`, поворот — в градусах. */
data class CroissantPlacedTileDto(
    val id: String,
    val x: Int,
    val y: Int,
    /** Код типа тайла в каталоге состояния. */
    val tile: String,
    val rotation: Int,
)

/** Участок тайла в составе связного участка сети. */
data class CroissantSpotMemberDto(
    val tileId: String,
    val index: Int,
)

/** Кондитер на участке: чей и на каком тайле стоит. */
data class CroissantConfectionerDto(
    val playerId: UUID,
    val tileId: String,
)

/**
 * Связный участок сети: пекарни и тропы, объединённые через границы клеток, сыроварня (один тайл) и луга.
 * [open] — сколько выходов ещё смотрит в пустые клетки; [bakeries] — у луга: id примыкающих пекарен.
 */
data class CroissantSpotDto(
    val id: String,
    val type: CroissantSpotType,
    val members: List<CroissantSpotMemberDto>,
    /** Сколько выходов участка смотрит в пустые клетки; у завершённого 0. */
    val open: Int,
    /** Вывески в пекарне. */
    val signs: Int,
    val confectioners: List<CroissantConfectionerDto>,
    val complete: Boolean,
    /** У луга — id примыкающих пекарен, у остальных участков пусто. */
    val bakeries: List<String>,
)

/** Выложенный тайл, ждущий кондитера: [spots] — куда его можно поставить, [completed] — что завершилось этим тайлом. */
data class CroissantPendingDto(
    val tileId: String,
    val spots: List<String>,
    val completed: List<String>,
)

/** Игрок «Круассана»: поля core и «Золотые крошки» с остатком кондитеров. */
data class CroissantPlayerDto(
    val id: UUID,
    /** Что игрок может сделать сейчас (PLACE_TILE или PLACE_CONFECTIONER, SKIP_CONFECTIONER). */
    val moveOptions: List<MoveOptionDto>,
    /** Накопленные «Золотые крошки». */
    val crumbs: Int,
    /** Непоставленные кондитеры. */
    val confectioners: Int,
)

/**
 * Состояние партии «Круассана»: блоки core и поля игры — поле сети, связные участки, каталог тайлов и текущий тайл.
 * Порядка колоды в состоянии нет: он выводится из seed партии на сервере.
 */
data class CroissantSessionStateDto(
    @field:Schema(type = "string", allowableValues = [STAGE_PLAY])
    val stage: String,
    val players: List<CroissantPlayerDto>,
    val timer: SessionTimerDto?,
    val turns: SessionTurnsDto,
    val result: SessionResultDto?,
    /** Выложенные тайлы; адресуются по id `x:y`. */
    val board: List<CroissantPlacedTileDto>,
    /** Связные участки сети. */
    val spots: List<CroissantSpotDto>,
    /** Каталог типов тайлов: по нему клиент рисует доску. */
    val tiles: List<CroissantTileDto>,
    /** Выложенный тайл, ждущий кондитера; null — шаг кондитера не сейчас. */
    val pending: CroissantPendingDto?,
    /** Тайл, который выкладывает ходящий; null — колода кончилась. */
    val currentTile: String?,
    /** Сколько тайлов колоды уже открыто. */
    val deckPos: Int,
    /** Размер колоды партии без стартового тайла. */
    val deckSize: Int,
) : SessionStateDto

/** Параметры хода «Круассана» (`data` тела хода): клетка и поворот либо участок для кондитера. */
data class CroissantMoveDataDto(
    /** Клетка выкладки по x; у PLACE_CONFECTIONER не нужна. */
    val x: Int? = null,
    /** Клетка выкладки по y. */
    val y: Int? = null,
    /** Поворот тайла в градусах: 0, 90, 180, 270. */
    val rotation: Int? = null,
    /** Участок сети для PLACE_CONFECTIONER (id из state.spots). */
    val spotId: String? = null,
)
