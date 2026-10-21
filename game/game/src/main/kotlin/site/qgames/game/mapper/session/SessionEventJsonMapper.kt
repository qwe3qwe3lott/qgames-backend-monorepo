package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.api.TimeControl
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.session.GameMeta
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionMeta
import site.qgames.games.api.session.SessionRules
import site.qgames.games.api.session.SessionStarted
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsState
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Данные событий журнала партии ↔ JSON (колонка room_session_event.data). Класс данных определяется по типу события:
 * платформа и модули — [SessionEvents.dataClasses], игра — GameRules.eventClasses. В `started` части игры (настройки и
 * игровое состояние) разбираются классами модуля игры.
 */
@Component
class SessionEventJsonMapper(
    private val gameRegistry: GameRegistry,
    private val jsonMapper: JsonMapper,
) {

    fun write(event: SessionEvent): String = jsonMapper.writeValueAsString(event.data)

    fun read(gameCode: GameCode, type: String, json: String): SessionEvent {
        val rules = gameRegistry.rules(gameCode)
        val data: Any = when (type) {
            SessionEvents.STARTED -> readStarted(gameCode, jsonMapper.readTree(json))
            in SessionEvents.dataClasses -> jsonMapper.readValue(json, SessionEvents.dataClasses.getValue(type))
            else -> jsonMapper.readValue(json, rules.eventClasses[type] ?: error("Unknown event '$type' of game $gameCode"))
        }
        return SessionEvent(type, data)
    }

    private fun readStarted(gameCode: GameCode, node: JsonNode): SessionStarted {
        val rules = gameRegistry.rules(gameCode)
        val game = node.path("meta").path("game")
        val sessionRules = game.path("rules")
        val state = node.path("state")
        return SessionStarted(
            meta = SessionMeta(
                GameMeta(
                    code = game.path("code").asString(),
                    rules = SessionRules(
                        timeControl = jsonMapper.treeToValue(sessionRules.path("timeControl"), TimeControl::class.java),
                        settings = jsonMapper.treeToValue(sessionRules.path("settings"), rules.settingsClass),
                    ),
                ),
            ),
            state = SessionState(
                players = jsonMapper.readValue(jsonMapper.writeValueAsString(state.path("players")), PLAYERS),
                timer = state.path("timer").takeUnless { it.isNull || it.isMissingNode }
                    ?.let { jsonMapper.treeToValue(it, TimerState::class.java) },
                turns = jsonMapper.treeToValue(state.path("turns"), TurnsState::class.java),
                game = jsonMapper.treeToValue(state.path("game"), rules.gameStateClass),
            ),
        )
    }

    companion object {
        private val PLAYERS = object : TypeReference<List<PlayerState>>() {}
    }
}
