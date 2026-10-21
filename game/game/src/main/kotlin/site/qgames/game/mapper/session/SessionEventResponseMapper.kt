package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.EmptyEventDataDto
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.session.MoveOptionDto
import site.qgames.game.gatewayapi.dto.session.NothingMovedDto
import site.qgames.game.gatewayapi.dto.session.NothingSessionStateDto
import site.qgames.game.gatewayapi.dto.session.SessionEndedFrame
import site.qgames.game.gatewayapi.dto.session.SessionGameDto
import site.qgames.game.gatewayapi.dto.session.SessionMetaDto
import site.qgames.game.gatewayapi.dto.session.SessionMovedDto
import site.qgames.game.gatewayapi.dto.session.SessionMovedFrame
import site.qgames.game.gatewayapi.dto.session.SessionPlayerChangedFrame
import site.qgames.game.gatewayapi.dto.session.SessionPlayerDto
import site.qgames.game.gatewayapi.dto.session.SessionSnapshotDto
import site.qgames.game.gatewayapi.dto.session.SessionStartedDto
import site.qgames.game.gatewayapi.dto.session.SessionStartedFrame
import site.qgames.game.gatewayapi.dto.session.SessionStateDto
import site.qgames.game.gatewayapi.dto.session.SessionTimerChangedFrame
import site.qgames.game.gatewayapi.dto.session.SessionTimerDto
import site.qgames.game.gatewayapi.dto.session.SessionTimerStatus
import site.qgames.game.gatewayapi.dto.session.SessionTurnsChangedFrame
import site.qgames.game.gatewayapi.dto.session.SessionTurnsDto
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.SessionSnapshotModel
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionMeta
import site.qgames.games.api.session.SessionStarted
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsState
import site.qgames.game.gatewayapi.dto.session.SessionStreamEventTypes
import site.qgames.games.nothing.NothingGameState
import site.qgames.games.nothing.NothingMoved
import java.time.Instant

/**
 * Данные потока партии (поле `data:` SSE) → DTO контракта game-user-gateway-sync-api (SessionStreamEvent). Только
 * преобразование, без обращений к данным. Состояние и игровые события — классы модуля игры, поэтому новая игра
 * добавляет сюда свои ветки (как и свои DTO в контракт).
 */
@Component
class SessionEventResponseMapper(
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
) {

    fun toSnapshot(snapshot: SessionSnapshotModel): SessionSnapshotDto {
        val session = snapshot.state.session
        return SessionSnapshotDto(
            seq = snapshot.state.seq,
            status = SessionStatus.valueOf(session.status.name),
            serverTime = snapshot.serverTime,
            meta = toResponse(session.meta),
            state = toResponse(session.meta.game.code, session.state),
        )
    }

    /** Кадр события журнала `{seq, at, data}`; [event] уже замаскирован для зрителя. */
    fun toFrame(seq: Long, at: Instant, event: SessionEvent): Any = when (event.type) {
        SessionEvents.STARTED -> {
            val started = event.data as SessionStarted
            SessionStartedFrame(seq, at, SessionStartedDto(toResponse(started.meta), toResponse(started.meta.game.code, started.state)))
        }
        SessionEvents.FINISHED, SessionEvents.CANCELLED -> SessionEndedFrame(seq, at, EmptyEventDataDto())
        SessionEvents.PLAYER_CHANGED -> SessionPlayerChangedFrame(seq, at, toResponse(event.data as PlayerState))
        SessionEvents.TURNS_CHANGED -> SessionTurnsChangedFrame(seq, at, toResponse(event.data as TurnsState))
        SessionEvents.TIMER_CHANGED -> SessionTimerChangedFrame(seq, at, toResponse(event.data as TimerState))
        SessionStreamEventTypes.MOVED -> SessionMovedFrame(seq, at, toMoved(event.data))
        else -> error("No DTO for session event ${event.type}")
    }

    private fun toResponse(meta: SessionMeta): SessionMetaDto {
        val rules = meta.game.rules
        val timeControl = RoomTimeControlModel(RoomTimeControlMode.valueOf(rules.timeControl.mode.name), rules.timeControl.moveSeconds)
        return SessionMetaDto(
            SessionGameDto(
                code = GameCode.fromCode(meta.game.code) ?: error("Unknown game code ${meta.game.code}"),
                rules = roomSettingsResponseMapper.toResponse(RoomSettingsModel(timeControl, rules.settings)),
            ),
        )
    }

    private fun toResponse(gameCode: String, state: SessionState<Any>): SessionStateDto = when (val game = state.game) {
        is NothingGameState -> NothingSessionStateDto(
            players = state.players.map(::toResponse),
            timer = state.timer?.let(::toResponse),
            turns = toResponse(state.turns),
            moves = game.moves,
        )
        else -> error("No DTO for the state of game $gameCode")
    }

    private fun toMoved(data: Any): SessionMovedDto = when (data) {
        is NothingMoved -> NothingMovedDto(playerId = data.playerId, type = data.type, timeout = data.timeout)
        else -> error("No DTO for moved ${data::class.qualifiedName}")
    }

    private fun toResponse(player: PlayerState) = SessionPlayerDto(
        id = player.id,
        moveOptions = player.moveOptions.map { MoveOptionDto(it.type, it.data) },
    )

    private fun toResponse(turns: TurnsState) = SessionTurnsDto(turns.currentPlayerId, turns.round, turns.rounds)

    private fun toResponse(timer: TimerState) =
        SessionTimerDto(SessionTimerStatus.valueOf(timer.status.name), timer.deadline, timer.pausedAt)
}
