package site.qgames.games.checkers

/** Клетка доски `[x, y]`, отсчёт с 1. */
internal data class Square(val x: Int, val y: Int) {

    fun toList(): List<Int> = listOf(x, y)

    fun step(direction: Direction): Square = Square(x + direction.dx, y + direction.dy)

    companion object {
        /** Клетка из `[x, y]`; null — не пара чисел. */
        fun of(square: List<Int>): Square? = if (square.size == 2) Square(square[0], square[1]) else null
    }
}

/** Диагональное направление. */
internal data class Direction(val dx: Int, val dy: Int) {
    companion object {
        val ALL = listOf(Direction(1, 1), Direction(-1, 1), Direction(1, -1), Direction(-1, -1))
    }
}

/**
 * Допустимый ход: шашка [piece], полный путь [path] (с начальной клеткой), сбитые шашки [captured] в порядке взятия и
 * признак превращения в дамку [crowned].
 */
internal data class LegalMove(
    val piece: CheckersPiece,
    val path: List<Square>,
    val captured: List<CheckersPiece>,
    val crowned: Boolean,
)

/**
 * Генерация ходов строго по флагам [rules] (вариантов генератор не знает):
 *
 * - тихий ход простой — на соседнюю клетку вперёд (и назад, если [CheckersAdvancedRules.menMoveBackward]); дамки — на любое
 *   число свободных клеток ([CheckersAdvancedRules.flyingKings]) или на одну;
 * - взятие — прыжок через шашку соперника на свободную клетку за ней; простая бьёт вперёд (и назад, если
 *   [CheckersAdvancedRules.menCaptureBackward]), дальнобойная дамка — через любое число свободных клеток до и после;
 * - многократное взятие: если можно бить дальше, бить обязательно — путь заканчивается только там, откуда продолжения
 *   нет. Дамка после прыжка встаёт только на те клетки, с которых бой продолжается, если такие есть;
 * - турецкий удар ([CheckersAdvancedRules.removeCapturedAfterMove]): сбитые остаются на доске до конца хода, через них не
 *   прыгают второй раз и не проходят;
 * - простая, дошедшая до последнего ряда посреди взятия, бьёт дальше как дамка ([CheckersAdvancedRules.promoteMidCapture]);
 *   в конце хода на последнем ряду становится дамкой;
 * - обязательное взятие ([CheckersAdvancedRules.captureRequired]) и только максимальная ветка
 *   ([CheckersAdvancedRules.maxCaptureRequired]).
 */
internal class CheckersBoard(
    private val rules: CheckersAdvancedRules,
    private val pieces: List<CheckersPiece>,
) {

    private val bySquare: Map<Square, CheckersPiece> =
        pieces.associateBy { Square.of(it.square) ?: error("Piece ${it.id} has a bad square ${it.square}") }

    /** Все допустимые ходы стороны [color]. */
    fun legalMoves(color: CheckersColor): List<LegalMove> {
        val own = pieces.filter { it.color == color }
        val captures = own.flatMap(::captures)
        val allowedCaptures = if (rules.maxCaptureRequired && captures.isNotEmpty()) {
            val max = captures.maxOf { it.captured.size }
            captures.filter { it.captured.size == max }
        } else {
            captures
        }
        return if (rules.captureRequired && allowedCaptures.isNotEmpty()) allowedCaptures else allowedCaptures + own.flatMap(::quietMoves)
    }

    /** Доска после хода [move]: сбитые сняты, шашка на последней клетке пути, при превращении — дамка. */
    fun after(move: LegalMove): List<CheckersPiece> {
        val captured = move.captured.map { it.id }.toSet()
        return pieces.filter { it.id !in captured }.map {
            if (it.id != move.piece.id) {
                it
            } else {
                it.copy(square = move.path.last().toList(), kind = if (move.crowned) CheckersPieceKind.KING else it.kind)
            }
        }
    }

    private fun quietMoves(piece: CheckersPiece): List<LegalMove> {
        val from = square(piece)
        val king = piece.kind == CheckersPieceKind.KING
        val directions = if (king || rules.menMoveBackward) Direction.ALL else forward(piece.color)
        return directions.flatMap { direction ->
            buildList {
                var to = from.step(direction)
                while (inside(to) && bySquare[to] == null) {
                    add(LegalMove(piece, listOf(from, to), emptyList(), crowned = !king && to.y == lastRow(piece.color)))
                    if (!king || !rules.flyingKings) break
                    to = to.step(direction)
                }
            }
        }
    }

    private fun captures(piece: CheckersPiece): List<LegalMove> {
        val origin = square(piece)
        val wasKing = piece.kind == CheckersPieceKind.KING
        val result = mutableListOf<LegalMove>()

        fun continueFrom(at: Square, king: Boolean, path: List<Square>, captured: List<CheckersPiece>) {
            for (jump in jumps(piece.color, origin, at, king, captured)) {
                val taken = captured + jump.victim
                val landings = jump.landings.map { landing ->
                    val kingAfter = king || (rules.promoteMidCapture && landing.y == lastRow(piece.color))
                    Landing(landing, kingAfter, jumps(piece.color, origin, landing, kingAfter, taken).isNotEmpty())
                }
                // Если с какой-то клетки бой продолжается, остановиться на другой нельзя.
                val continuing = landings.filter { it.canContinue }
                for (landing in continuing.ifEmpty { landings }) {
                    if (landing.canContinue) {
                        continueFrom(landing.square, landing.king, path + landing.square, taken)
                    } else {
                        val crowned = !wasKing && (landing.king || landing.square.y == lastRow(piece.color))
                        result += LegalMove(piece, path + landing.square, taken, crowned)
                    }
                }
            }
        }

        continueFrom(origin, wasKing, listOf(origin), emptyList())
        return result
    }

    /** Прыжки из [at]: какая шашка соперника бьётся и на какие клетки за ней можно встать. */
    private fun jumps(color: CheckersColor, origin: Square, at: Square, king: Boolean, captured: List<CheckersPiece>): List<Jump> {
        val directions = if (king || rules.menCaptureBackward) Direction.ALL else forward(color)
        return directions.mapNotNull { direction ->
            var over = at.step(direction)
            if (king && rules.flyingKings) {
                while (inside(over) && isFree(over, origin, captured)) over = over.step(direction)
            }
            if (!inside(over) || isFree(over, origin, captured)) return@mapNotNull null
            val victim = bySquare.getValue(over)
            if (victim.color == color || victim in captured) return@mapNotNull null
            val landings = buildList {
                var landing = over.step(direction)
                while (inside(landing) && isFree(landing, origin, captured)) {
                    add(landing)
                    if (!king || !rules.flyingKings) break
                    landing = landing.step(direction)
                }
            }
            landings.takeIf { it.isNotEmpty() }?.let { Jump(victim, it) }
        }
    }

    /**
     * Свободна ли клетка во время хода: начальная клетка шашки уже свободна, сбитые освобождают клетку сразу только
     * без турецкого удара.
     */
    private fun isFree(square: Square, origin: Square, captured: List<CheckersPiece>): Boolean {
        if (square == origin) return true
        val occupant = bySquare[square] ?: return true
        return !rules.removeCapturedAfterMove && occupant in captured
    }

    private fun inside(square: Square) = square.x in 1..rules.boardSize && square.y in 1..rules.boardSize

    private fun square(piece: CheckersPiece) = Square.of(piece.square)!!

    private fun forward(color: CheckersColor): List<Direction> {
        val dy = if (color == CheckersColor.WHITE) 1 else -1
        return listOf(Direction(1, dy), Direction(-1, dy))
    }

    private fun lastRow(color: CheckersColor) = if (color == CheckersColor.WHITE) rules.boardSize else 1

    private data class Jump(val victim: CheckersPiece, val landings: List<Square>)

    private data class Landing(val square: Square, val king: Boolean, val canContinue: Boolean)
}
