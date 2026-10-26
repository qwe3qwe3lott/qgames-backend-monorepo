package site.qgames.game.mapper.session

import java.time.Instant
import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.SessionStatus
import site.qgames.game.api.dto.session.CheckersMoveRulesDto
import site.qgames.game.api.dto.session.CheckersSessionStateDto
import site.qgames.game.api.dto.session.DeleteData
import site.qgames.game.api.dto.session.NothingSessionStateDto
import site.qgames.game.api.dto.session.PatchData
import site.qgames.game.api.dto.session.PutData
import site.qgames.game.api.dto.session.SessionEventDelete
import site.qgames.game.api.dto.session.SessionEventInfo
import site.qgames.game.api.dto.session.SessionEventMethod
import site.qgames.game.api.dto.session.SessionEventPatch
import site.qgames.game.api.dto.session.SessionEventPut
import site.qgames.game.api.dto.session.SessionGameDto
import site.qgames.game.api.dto.session.SessionMetaDto
import site.qgames.game.api.dto.session.SessionMoveRulesDto
import site.qgames.game.api.dto.session.SessionPatchEvent
import site.qgames.game.api.dto.session.SessionSnapshotDto
import site.qgames.game.api.dto.session.SessionStateDto
import site.qgames.game.api.dto.session.SessionStreamEventType
import site.qgames.game.model.session.SessionSnapshotModel
import site.qgames.games.core.SessionEngine
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEvents
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Данные потока партии (поле `data:` SSE) → DTO контракта game-api (SessionStreamEvent). Только
 * преобразование, без обращений к данным.
 *
 * Снапшот — документ партии `{status, meta, state}` зрителя, разобранный строго (лишние поля — ошибка) в схему
 * конкретной игры: так документ, к которому клиент применяет события, всегда совпадает с контрактом. Новая игра
 * добавляет сюда ветку своего DTO состояния. Событие — патч документа: путь и значение уходят как есть.
 */
@Component
class SessionEventResponseMapper(
    private val jsonMapper: JsonMapper,
    private val sessionEventJsonMapper: SessionEventJsonMapper,
) {

    fun toSnapshot(snapshot: SessionSnapshotModel): SessionSnapshotDto {
        val document = snapshot.state.document
        val meta = meta(document[SessionEngine.META])
        return SessionSnapshotDto(
            seq = snapshot.state.seq,
            status = SessionStatus.valueOf(document[SessionEngine.STATUS] as String),
            serverTime = snapshot.serverTime,
            meta = meta,
            state = strict(document[SessionEvents.STATE], stateClass(meta.game.code)),
        )
    }

    /** Тип сообщения SSE для события: метод в нижнем регистре. */
    fun toType(event: SessionEvent): String = SessionStreamEventType.valueOf(event.method.name).code

    /** Кадр события журнала `{seq, at, method, code, data}`; [event] уже замаскирован для зрителя. */
    fun toFrame(seq: Long, at: Instant, event: SessionEvent): SessionPatchEvent {
        val method = SessionEventMethod.valueOf(event.method.name)
        val path = sessionEventJsonMapper.path(event.path)
        return when (method) {
            SessionEventMethod.PUT -> SessionEventPut(seq, at, method, event.code, PutData(path, event.value))
            SessionEventMethod.PATCH -> SessionEventPatch(seq, at, method, event.code, PatchData(path, objectOf(event)))
            SessionEventMethod.DELETE -> SessionEventDelete(seq, at, method, event.code, DeleteData(path))
            SessionEventMethod.INFO -> SessionEventInfo(seq, at, method, event.code, objectOf(event))
        }
    }

    /**
     * meta разбирается строго, а правила хода — схемой игры из game.code: у union без дискриминатора Jackson вид сам не
     * выберет.
     */
    private fun meta(tree: Any?): SessionMetaDto {
        @Suppress("UNCHECKED_CAST")
        val meta = tree as? Map<String, Any?> ?: error("Session meta must be an object")
        val unknown = meta.keys - setOf(GAME, MOVE_RULES)
        check(unknown.isEmpty()) { "Unknown fields $unknown in the session meta" }
        val game = strict(meta[GAME], SessionGameDto::class.java)
        return SessionMetaDto(game, meta[MOVE_RULES]?.let { strict(it, moveRulesClass(game.code)) })
    }

    private fun stateClass(gameCode: GameCode): Class<out SessionStateDto> = when (gameCode) {
        GameCode.NOTHING -> NothingSessionStateDto::class.java
        GameCode.CHECKERS -> CheckersSessionStateDto::class.java
        else -> error("No DTO for the state of game $gameCode")
    }

    private fun moveRulesClass(gameCode: GameCode): Class<out SessionMoveRulesDto> = when (gameCode) {
        GameCode.CHECKERS -> CheckersMoveRulesDto::class.java
        else -> error("No DTO for the move rules of game $gameCode")
    }

    private fun <T : Any> strict(tree: Any?, type: Class<T>): T =
        jsonMapper.readerFor(type)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(jsonMapper.valueToTree<JsonNode>(tree))

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(event: SessionEvent): Map<String, Any?> =
        event.value as? Map<String, Any?> ?: error("Value of ${event.method} ${event.code} must be an object")

    private companion object {
        const val GAME = "game"
        const val MOVE_RULES = "moveRules"
    }
}
