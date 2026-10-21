package site.qgames.game.service.room

import org.springframework.stereotype.Service
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * Собирает комнату целиком: участники с профилями из реплики, настройки, текущая (или последняя) партия кратко —
 * её состояние приходит только из потока партии. Вызывается внутри транзакций RoomService и RoomSessionService.
 */
@Service
class RoomDetailsService(
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val userAuthUserRepository: UserAuthUserRepository,
    private val gameRegistry: GameRegistry,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun details(room: RoomModel): RoomDetailsModel {
        val rules = gameRegistry.rules(room.gameCode)
        val members = roomMemberRepository.findAll(room.id)
        val profiles = userAuthUserRepository.findByIds(members.map { it.userId })
        return RoomDetailsModel(
            room = room,
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            settings = RoomSettingsModel(room.timeControl, jsonMapper.readValue(room.gameSettings, rules.settingsClass)),
            members = members.map { RoomMemberDetailsModel(it, profiles[it.userId]) },
            session = room.currentSessionId?.let(roomSessionRepository::findById) ?: roomSessionRepository.findLatest(room.id),
            serverTime = clock.instant(),
        )
    }
}
