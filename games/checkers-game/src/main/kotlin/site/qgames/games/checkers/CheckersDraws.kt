package site.qgames.games.checkers

/**
 * Ничьи по правилам (кроме соглашения): троекратное повторение позиции и правила окончаний вариантов. Счёт ходов
 * окончаний — [CheckersGameState.materialMoves]: ходов обеих сторон с последнего взятия или превращения, то есть с
 * момента установления соотношения сил.
 *
 * - Русские (правила вида спорта «шашки»): три и более дамки против одной дамки — сильнейшая сторона не взяла её своим
 *   15-м ходом; одинокая дамка на большой дороге (главная диагональ, x = y) против трёх дамок, двух дамок и простой,
 *   дамки и двух простых или трёх простых — 5-й ход сильнейшей стороны («выигранная позиция» не определяется: ничья, если
 *   дамка не взята); у обеих сторон есть дамки, соотношение не меняется — в 2–3-фигурных окончаниях по 5 ходов, в 4–5 —
 *   по 30, в 6–7 — по 60.
 * - Международные (FMJD): одинокая дамка против трёх фигур хотя бы с одной дамкой — по 16 ходов, против двух фигур и
 *   меньше хотя бы с одной дамкой — по 5 ходов.
 * - Английские — особых правил окончаний нет.
 *
 * «По N ходов» — обе стороны сделали по N ходов; «N-й ход сильнейшей стороны» — проверяется после её хода.
 */
internal object CheckersDraws {

    /** Сколько раз позиция должна встретиться для ничьей. */
    const val REPETITIONS = 3

    /** Ключ позиции для повторения: чья очередь и шашки (цвет, вид, клетка) без id. */
    fun positionKey(toMove: CheckersColor, pieces: List<CheckersPiece>): String =
        toMove.name.first() + "|" + pieces.map { "${it.color.name.first()}${it.kind.name.first()}${it.square[0]},${it.square[1]}" }.sorted().joinToString(";")

    /**
     * Ничья по правилу окончаний после хода стороны [mover]: [pieces] — позиция после хода, [materialMoves] — счётчик с
     * этим ходом.
     */
    fun endgame(variant: CheckersVariant, pieces: List<CheckersPiece>, mover: CheckersColor, materialMoves: Int): Boolean = when (variant) {
        CheckersVariant.RUSSIAN -> russian(pieces, mover, materialMoves)
        CheckersVariant.INTERNATIONAL -> international(pieces, materialMoves)
        CheckersVariant.ENGLISH -> false
    }

    private fun russian(pieces: List<CheckersPiece>, mover: CheckersColor, materialMoves: Int): Boolean {
        val strongMoves = (materialMoves + 1) / 2
        val loneKing = CheckersColor.entries.any { strong ->
            val weak = side(pieces, strong.opponent())
            val own = side(pieces, strong)
            if (!weak.isLoneKing || mover != strong) return@any false
            val king = weak.pieces.single()
            when {
                king.square[0] == king.square[1] && own.count == 3 -> strongMoves >= 5
                own.kings >= 3 -> strongMoves >= 15
                else -> false
            }
        }
        val white = side(pieces, CheckersColor.WHITE)
        val black = side(pieces, CheckersColor.BLACK)
        val balanced = white.kings > 0 && black.kings > 0 && when (pieces.size) {
            in 2..3 -> materialMoves >= 2 * 5
            in 4..5 -> materialMoves >= 2 * 30
            in 6..7 -> materialMoves >= 2 * 60
            else -> false
        }
        return loneKing || balanced
    }

    private fun international(pieces: List<CheckersPiece>, materialMoves: Int): Boolean = CheckersColor.entries.any { strong ->
        val own = side(pieces, strong)
        if (!side(pieces, strong.opponent()).isLoneKing || own.kings == 0) return@any false
        when (own.count) {
            3 -> materialMoves >= 2 * 16
            in 1..2 -> materialMoves >= 2 * 5
            else -> false
        }
    }

    private fun side(pieces: List<CheckersPiece>, color: CheckersColor) = Side(pieces.filter { it.color == color })

    private class Side(val pieces: List<CheckersPiece>) {
        val count = pieces.size
        val kings = pieces.count { it.kind == CheckersPieceKind.KING }
        val isLoneKing = count == 1 && kings == 1
    }
}
