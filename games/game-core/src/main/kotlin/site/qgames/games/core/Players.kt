package site.qgames.games.core

import site.qgames.common.docpatch.PathSegment
import java.util.UUID

/**
 * Игрок партии. Игра дописывает сюда свои атрибуты игрока (например, цвет в шашках).
 *
 * Поля игры ([game], объект класса GameRules.playerClass) в документе лежат прямо в объекте игрока рядом с полями
 * core (`{id, moveOptions, color}`), а не вложенным объектом, и не совпадают с ними по именам ([FIELDS]). Документ
 * собирает и читает SessionEngine; сам [PlayerState] в JSON напрямую не переводится.
 */
data class PlayerState(
    val id: UUID,
    /** Что игрок может сделать сейчас; чужим зрителям — пустой список. */
    val moveOptions: List<MoveOption>,
    /** Поля игры у игрока; null — у игры их нет. */
    val game: Any? = null,
) {
    companion object {
        /** Поля core в объекте игрока: поля игры не могут называться так же. */
        val FIELDS = setOf("id", "moveOptions")
    }
}

/** Вариант хода: тот же конверт, что тело хода (`type` и параметры `data`). */
data class MoveOption(
    val type: String,
    val data: Map<String, Any?>? = null,
)

/**
 * Игроки (`state.players`) и их варианты ходов. Варианты считает игра, а платформа после каждого изменения выдаёт `move-options-changed` (`PUT ["state", "players", {id}, "moveOptions"]`) тем игрокам, у которых набор
 * изменился. Варианты хода видит только сам игрок, остальным значение маскируется в `[]`.
 */
object Players {

    /** Варианты ходов игрока изменились. */
    const val MOVE_OPTIONS_CHANGED = "move-options-changed"

    /** Ключ блока в состоянии. */
    const val PLAYERS = "players"

    private const val MOVE_OPTIONS = "moveOptions"

    /** Игроки новой партии в порядке мест; [game] — поля игры у игрока (у игры без полей игрока — пусто). */
    fun initial(players: List<UUID>, game: Map<UUID, Any> = emptyMap()): List<PlayerState> =
        players.map { PlayerState(it, emptyList(), game[it]) }

    /** События `move-options-changed` для игроков, у которых варианты ходов в [state] отличаются от [options]. */
    fun <G : Any> changes(state: SessionState<G>, options: (UUID) -> List<MoveOption>): List<SessionEvent> =
        state.players.mapNotNull { player ->
            val actual = options(player.id)
            if (actual == player.moveOptions) null else moveOptionsChanged(player.id, actual)
        }

    fun moveOptionsChanged(playerId: UUID, options: List<MoveOption>): SessionEvent =
        SessionEvents.put(
            MOVE_OPTIONS_CHANGED,
            SessionEvents.path(SessionEvents.STATE, PLAYERS, SessionEvents.id(playerId), MOVE_OPTIONS),
            options,
        )

    /** Маска события `move-options-changed` для [viewer]; null — событие другое. */
    fun mask(event: SessionEvent, viewer: ViewerKey): SessionEvent? {
        if (event.code != MOVE_OPTIONS_CHANGED) return null
        val playerId = (event.path.getOrNull(2) as? PathSegment.Id)?.id?.let(UUID::fromString)
            ?: error("$MOVE_OPTIONS_CHANGED without a player id in the path: ${event.path}")
        return if (viewer.sees(playerId)) event else event.copy(value = emptyList<Any?>())
    }

    /** Игрок в начальном состоянии для [viewer]: чужие варианты ходов скрыты. */
    fun mask(player: PlayerState, viewer: ViewerKey): PlayerState =
        if (viewer.sees(player.id)) player else player.copy(moveOptions = emptyList())
}
