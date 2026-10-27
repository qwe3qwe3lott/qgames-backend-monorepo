package site.qgames.games.checkers

import java.util.UUID
import site.qgames.games.core.TimeControlSettings

/** Вариант правил: база advanced-правил (доска, первый ход, флаги по умолчанию — [CheckersAdvancedRules.of]). */
enum class CheckersVariant {
    RUSSIAN,
    INTERNATIONAL,
    ENGLISH,
}

enum class CheckersColor {
    WHITE,
    BLACK,
    ;

    fun opponent(): CheckersColor = if (this == WHITE) BLACK else WHITE
}

enum class CheckersPieceKind {
    /** Простая. */
    MAN,

    /** Дамка. */
    KING,
}

/** Пресет времени simple-настроек: официальный контроль времени варианта или без таймера. */
enum class CheckersTime {
    /** Без таймера. */
    NONE,

    /** Классика. */
    CLASSIC,

    /** Быстрые шашки. */
    RAPID,

    /** Блиц. */
    BLITZ,
}

/** Simple-настройки: вариант правил и пресет времени. */
data class CheckersSimpleSettings(
    val variant: CheckersVariant,
    val time: CheckersTime,
)

/**
 * Advanced-настройки партии: вариант (доска, первый ход, правила ничьих), часы и флаги правил хода, которые задаёт
 * владелец комнаты. Итоговые правила хода — [CheckersAdvancedRules.of].
 */
data class CheckersAdvancedSettings(
    val variant: CheckersVariant,
    val timeControl: TimeControlSettings,
    val menMoveBackward: Boolean,
    val menCaptureBackward: Boolean,
    val flyingKings: Boolean,
    val captureRequired: Boolean,
    val maxCaptureRequired: Boolean,
    val promoteMidCapture: Boolean,
    val removeCapturedAfterMove: Boolean,
)

/** Границы настроек для формы: доступные варианты правил и часы их пресетов. */
data class CheckersSettingsLimits(
    val variants: List<CheckersVariant>,
    val timePresets: List<CheckersTimePreset>,
)

/** Официальный контроль времени варианта: часы пресета [time] (кроме NONE). */
data class CheckersTimePreset(
    val variant: CheckersVariant,
    val time: CheckersTime,
    val baseSeconds: Int,
    val incrementSeconds: Int,
)

/**
 * Итоговые правила хода (`meta.advancedRules`): по этим флагам клиент подсказывает путь, а сервер генерирует и
 * проверяет ходы. Клиент вариантов не знает — только флаги. Доска и первый ход выводятся из варианта, флаги — из
 * advanced-настроек.
 */
data class CheckersAdvancedRules(
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
) {
    companion object {

        /** Русские шашки. */
        val RUSSIAN = CheckersAdvancedRules(
            boardSize = 8,
            firstColor = CheckersColor.WHITE,
            menMoveBackward = false,
            menCaptureBackward = true,
            flyingKings = true,
            captureRequired = true,
            maxCaptureRequired = false,
            promoteMidCapture = true,
            removeCapturedAfterMove = true,
        )

        /** Международные шашки (FMJD): доска 10×10, брать максимум, дамкой становятся только в конце хода. */
        val INTERNATIONAL = CheckersAdvancedRules(
            boardSize = 10,
            firstColor = CheckersColor.WHITE,
            menMoveBackward = false,
            menCaptureBackward = true,
            flyingKings = true,
            captureRequired = true,
            maxCaptureRequired = true,
            promoteMidCapture = false,
            removeCapturedAfterMove = true,
        )

        /**
         * Английские шашки (чекерс): первыми ходят чёрные, простая бьёт только вперёд, дамка ходит и бьёт на одну
         * клетку. Отдельный флаг «превращение завершает ход» не нужен: вперёд с последнего ряда бить некуда, а без
         * [promoteMidCapture] простая не продолжает бой дамкой.
         */
        val ENGLISH = CheckersAdvancedRules(
            boardSize = 8,
            firstColor = CheckersColor.BLACK,
            menMoveBackward = false,
            menCaptureBackward = false,
            flyingKings = false,
            captureRequired = true,
            maxCaptureRequired = false,
            promoteMidCapture = false,
            removeCapturedAfterMove = true,
        )

        /** Правила хода варианта по умолчанию: из них simple-настройки выводят флаги advanced. */
        fun of(variant: CheckersVariant): CheckersAdvancedRules = when (variant) {
            CheckersVariant.RUSSIAN -> RUSSIAN
            CheckersVariant.INTERNATIONAL -> INTERNATIONAL
            CheckersVariant.ENGLISH -> ENGLISH
        }

        /** Итоговые правила партии: доска и первый ход — из варианта, флаги — из advanced-настроек. */
        fun of(settings: CheckersAdvancedSettings): CheckersAdvancedRules = of(settings.variant).copy(
            menMoveBackward = settings.menMoveBackward,
            menCaptureBackward = settings.menCaptureBackward,
            flyingKings = settings.flyingKings,
            captureRequired = settings.captureRequired,
            maxCaptureRequired = settings.maxCaptureRequired,
            promoteMidCapture = settings.promoteMidCapture,
            removeCapturedAfterMove = settings.removeCapturedAfterMove,
        )
    }
}

/**
 * Игровая часть состояния (в документе — поля `state.pieces`, `state.lastMove`, `state.quietMoves`,
 * `state.materialMoves`, `state.positionHistory`, `state.drawOfferedBy`).
 */
data class CheckersGameState(
    /** Шашки на доске; сбитые удаляются. */
    val pieces: List<CheckersPiece>,
    /** Последний ход; null — ходов ещё не было. */
    val lastMove: CheckersLastMove?,
    /** Сколько ходов подряд сделано только дамками без взятий (для ничьей). */
    val quietMoves: Int,
    /** Сколько ходов (обеих сторон) прошло с последнего взятия или превращения — для правил окончаний. */
    val materialMoves: Int,
    /** Ключи позиций (очередь + шашки) с последнего взятия или хода простой — для троекратного повторения. */
    val positionHistory: List<String>,
    /** Кто предложил ничью вместе со своим ходом; null — предложения нет. */
    val drawOfferedBy: UUID?,
)

/** Шашка: элемент массива `state.pieces`, адресуется по [id] (`w1…`, `b1…`). */
data class CheckersPiece(
    val id: String,
    val color: CheckersColor,
    val kind: CheckersPieceKind,
    /** Клетка `[x, y]`, отсчёт с 1; `[1, 1]` — тёмная клетка в углу белых. */
    val square: List<Int>,
)

/** Последний ход: шашка, полный путь (с начальной клеткой) и клетки сбитых шашек. */
data class CheckersLastMove(
    val pieceId: String,
    val path: List<List<Int>>,
    val captured: List<List<Int>>,
)

/** Поля игры у игрока (`state.players[]`): цвет. */
data class CheckersPlayer(
    val color: CheckersColor,
)

/** Ход: `{type: "MOVE", data: {path, offerDraw?}}`, `{type: "ACCEPT_DRAW"}` или `{type: "RESIGN"}`. */
data class CheckersMove(
    val type: String,
    val data: CheckersMoveData? = null,
)

/**
 * Параметры хода MOVE: полный путь шашки, включая начальную клетку, клетки — `[x, y]`; [offerDraw] — предложить ничью
 * вместе с этим ходом.
 */
data class CheckersMoveData(
    val path: List<List<Int>>,
    val offerDraw: Boolean = false,
)
