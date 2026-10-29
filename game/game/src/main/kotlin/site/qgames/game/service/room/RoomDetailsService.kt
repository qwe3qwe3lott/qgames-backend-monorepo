package site.qgames.game.service.room

import org.springframework.stereotype.Service
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import tools.jackson.databind.json.JsonMapper

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
) {

    fun details(room: RoomModel): RoomDetailsModel {
        val rules = gameRegistry.rules(room.gameCode)
        return RoomDetailsModel(
            room = room,
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            settings = settings(room),
            members = roomMemberRepository.findAllWithProfiles(room.id),
            session = room.currentSessionId?.let(roomSessionRepository::findById) ?: roomSessionRepository.findLatest(room.id),
        )
    }

    /** Настройки комнаты; настройки игры разобраны в классы модуля игры. */
    fun settings(room: RoomModel): RoomSettingsModel {
        val rules = gameRegistry.rules(room.gameCode)
        return RoomSettingsModel(
            room = room.options,
            simple = room.simpleSettings?.let { jsonMapper.readValue(it, rules.simpleClass) },
            advanced = jsonMapper.readValue(room.advancedSettings, rules.advancedClass),
        )
    }
}
