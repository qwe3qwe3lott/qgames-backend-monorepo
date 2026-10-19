package site.qgames.game.service.room

import org.springframework.stereotype.Service
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomSessionDetailsModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Собирает комнату целиком: участники с профилями из реплики, текущая (или последняя) партия с точки зрения [viewer].
 * Вызывается внутри транзакций RoomService и RoomSessionService.
 */
@Service
class RoomDetailsService(
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val userAuthUserRepository: UserAuthUserRepository,
    private val gameRegistry: GameRegistry,
    private val jsonMapper: JsonMapper,
) {

    fun details(room: RoomModel, viewer: UUID): RoomDetailsModel {
        val rules = gameRegistry.rules(room.gameType)
        val members = roomMemberRepository.findAll(room.id)
        val profiles = userAuthUserRepository.findByIds(members.map { it.userId })
        val session = room.currentSessionId?.let(roomSessionRepository::findById) ?: roomSessionRepository.findLatest(room.id)
        return RoomDetailsModel(
            room = room,
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            members = members.map { RoomMemberDetailsModel(it, profiles[it.userId]) },
            session = session?.let { sessionDetails(it, viewer) },
        )
    }

    private fun sessionDetails(session: RoomSessionModel, viewer: UUID): RoomSessionDetailsModel {
        val rules = gameRegistry.rules(session.gameType)
        return RoomSessionDetailsModel(
            session = session,
            players = roomSessionRepository.findPlayers(session.id),
            view = rules.view(jsonMapper.readValue(session.state, rules.stateClass), viewer),
        )
    }
}
