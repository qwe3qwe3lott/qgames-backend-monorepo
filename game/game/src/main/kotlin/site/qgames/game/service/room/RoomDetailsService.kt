package site.qgames.game.service.room

import org.springframework.stereotype.Service
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * Собирает комнату целиком: участники с профилями (join с таблицей user модуля user-auth), настройки, текущая (или последняя) партия кратко —
 * её состояние приходит только из потока партии. Вызывается внутри транзакций RoomService и RoomSessionService.
 */
@Service
class RoomDetailsService(
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val gameRegistry: GameRegistry,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun details(room: RoomModel): RoomDetailsModel {
        val rules = gameRegistry.rules(room.gameCode)
        return RoomDetailsModel(
            room = room,
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            settings = RoomSettingsModel(room.timeControl, jsonMapper.readValue(room.gameSettings, rules.settingsClass)),
            members = roomMemberRepository.findAllWithProfiles(room.id),
            session = room.currentSessionId?.let(roomSessionRepository::findById) ?: roomSessionRepository.findLatest(room.id),
            serverTime = clock.instant(),
        )
    }
}
