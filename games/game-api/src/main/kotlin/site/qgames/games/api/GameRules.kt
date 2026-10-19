package site.qgames.games.api

import java.util.UUID

/**
 * Правила одной игры. Реализация — детерминированные чистые функции: одинаковые состояние и ход дают одинаковый
 * результат на любом инстансе и при переигрывании журнала ходов. Случайность берётся только из seed.
 *
 * Состояние и ход сериализуются сервисом game в JSON (jsonb), поэтому [S] и [M] — простые data-классы
 * без вычисляемых свойств. При несовместимом изменении формата состояния увеличивается [stateSchemaVersion].
 */
interface GameRules<S : Any, M : Any> {

    /** Совпадает с именем значения GameType в контракте сервиса game. */
    val type: String
    val minPlayers: Int
    val maxPlayers: Int
    val stateClass: Class<S>
    val moveClass: Class<M>
    val stateSchemaVersion: Int

    /** Игроки перечислены в порядке мест. */
    fun initialState(players: List<UUID>, seed: Long): S

    fun applyMove(state: S, player: UUID, move: M): MoveResult<S>

    fun isFinished(state: S): Boolean

    /** То, что видит конкретный участник (игрок или наблюдатель). Здесь скрывается чужая закрытая информация. */
    fun view(state: S, viewer: UUID): Any
}

sealed interface MoveResult<out S> {

    data class Applied<S>(val state: S) : MoveResult<S>

    /** [code] — машиночитаемая причина, отдаётся клиенту. */
    data class Rejected(val code: String, val message: String) : MoveResult<Nothing>
}
