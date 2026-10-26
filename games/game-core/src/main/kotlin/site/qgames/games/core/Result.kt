package site.qgames.games.core

import java.util.UUID

/** Итог партии: исход, победители и причина окончания. */
data class ResultState(
    val outcome: ResultOutcome,
    /** Победители; у ничьей — пустой список. */
    val winnerIds: List<UUID>,
    /** Машиночитаемая причина окончания, её задаёт игра (например, `NO_PIECES`, `RESIGN`, `TIMEOUT`). */
    val reason: String,
)

enum class ResultOutcome {
    WIN,
    DRAW,
}

/**
 * Итог партии (`state.result`). После старта итога нет (`null`). Игра ставит итог событием `result-set`
 * (`PUT ["state", "result"]`, [set]) только в том же наборе событий, что и `session-finished`: это проверяет
 * платформа. У отменённой партии итога нет.
 */
object Result {

    /** Итог партии подведён. */
    const val RESULT_SET = "result-set"

    /** Ключ блока в состоянии. */
    const val RESULT = "result"

    fun set(result: ResultState): SessionEvent = SessionEvents.put(RESULT_SET, SessionEvents.path(SessionEvents.STATE, RESULT), result)

    /** Победа [winnerId] по причине [reason]. */
    fun win(winnerId: UUID, reason: String): SessionEvent = set(ResultState(ResultOutcome.WIN, listOf(winnerId), reason))

    /** Ничья по причине [reason]. */
    fun draw(reason: String): SessionEvent = set(ResultState(ResultOutcome.DRAW, emptyList(), reason))
}
