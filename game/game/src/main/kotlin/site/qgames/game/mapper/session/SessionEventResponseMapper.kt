package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.session.DeleteData
import site.qgames.game.gatewayapi.dto.session.NothingSessionStateDto
import site.qgames.game.gatewayapi.dto.session.PatchData
import site.qgames.game.gatewayapi.dto.session.PutData
import site.qgames.game.gatewayapi.dto.session.SessionEventDelete
import site.qgames.game.gatewayapi.dto.session.SessionEventInfo
import site.qgames.game.gatewayapi.dto.session.SessionEventMethod
import site.qgames.game.gatewayapi.dto.session.SessionEventPatch
import site.qgames.game.gatewayapi.dto.session.SessionEventPut
import site.qgames.game.gatewayapi.dto.session.SessionMetaDto
import site.qgames.game.gatewayapi.dto.session.SessionPatchEvent
import site.qgames.game.gatewayapi.dto.session.SessionSnapshotDto
import site.qgames.game.gatewayapi.dto.session.SessionStateDto
import site.qgames.game.gatewayapi.dto.session.SessionStreamEventType
import site.qgames.game.model.session.SessionSnapshotModel
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Данные потока партии (поле `data:` SSE) → DTO контракта game-user-gateway-sync-api (SessionStreamEvent). Только
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
        val meta = strict(document[SessionEngine.META], SessionMetaDto::class.java)
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

    private fun stateClass(gameCode: GameCode): Class<out SessionStateDto> = when (gameCode) {
        GameCode.NOTHING -> NothingSessionStateDto::class.java
        else -> error("No DTO for the state of game $gameCode")
    }

    private fun <T : Any> strict(tree: Any?, type: Class<T>): T =
        jsonMapper.readerFor(type)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(jsonMapper.valueToTree<JsonNode>(tree))

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(event: SessionEvent): Map<String, Any?> =
        event.value as? Map<String, Any?> ?: error("Value of ${event.method} ${event.code} must be an object")
}
