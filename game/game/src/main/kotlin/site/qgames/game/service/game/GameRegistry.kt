package site.qgames.game.service.game

import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomGameType
import site.qgames.games.api.GameRules

@Component
class GameRegistry(rules: List<GameRules<*, *>>) {

    private val byType: Map<RoomGameType, GameRules<Any, Any>> = rules.associate {
        @Suppress("UNCHECKED_CAST")
        RoomGameType.valueOf(it.type) to (it as GameRules<Any, Any>)
    }

    init {
        val missing = RoomGameType.entries - byType.keys
        check(missing.isEmpty()) { "No rules registered for game types $missing" }
    }

    fun rules(gameType: RoomGameType): GameRules<Any, Any> = byType.getValue(gameType)
}
