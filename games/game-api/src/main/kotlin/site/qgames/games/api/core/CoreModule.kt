package site.qgames.games.api.core

import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.session.ViewerKey
import java.util.UUID

/** Игрок партии (модуль core). Другие модули дописывают сюда свои атрибуты игрока (например, запас времени). */
data class PlayerState(
    val id: UUID,
    /** Что игрок может сделать сейчас; чужим зрителям — пустой список. */
    val moveOptions: List<MoveOption>,
)

/** Вариант хода: тот же конверт, что тело хода (`type` и параметры `data`). */
data class MoveOption(
    val type: String,
    val data: Map<String, Any?>? = null,
)

/**
 * Модуль core: игроки и их варианты ходов. Варианты считает игра, а модуль после каждого изменения выдаёт
 * `player-changed` тем игрокам, у которых набор изменился. Варианты хода видит только сам игрок.
 */
object CoreModule {

    fun initialPlayers(players: List<UUID>): List<PlayerState> = players.map { PlayerState(it, emptyList()) }

    /** События `player-changed` для игроков, у которых варианты ходов в [state] отличаются от [options]. */
    fun <G : Any> changes(state: SessionState<G>, options: (UUID) -> List<MoveOption>): List<SessionEvent> =
        state.players.mapNotNull { player ->
            val actual = options(player.id)
            if (actual == player.moveOptions) null else SessionEvent(SessionEvents.PLAYER_CHANGED, player.copy(moveOptions = actual))
        }

    fun <G : Any> evolve(state: SessionState<G>, player: PlayerState): SessionState<G> =
        state.copy(players = state.players.map { if (it.id == player.id) player else it })

    fun mask(player: PlayerState, viewer: ViewerKey): PlayerState =
        if (viewer.sees(player.id)) player else player.copy(moveOptions = emptyList())
}
