package site.qgames.games.api.result

import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import java.util.UUID

/** Итог партии (модуль result): исход, победители и причина окончания. */
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
 * Модуль result (`state.result`): итог партии. После старта итога нет (`null`). Игра ставит итог событием `result-set`
 * (`PUT ["state", "result"]`, [set]) только в том же наборе событий, что и `session-finished`: это проверяет
 * платформа. У отменённой партии итога нет.
 */
object ResultModule {

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
