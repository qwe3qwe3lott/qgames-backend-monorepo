package site.qgames.game.service.room

import org.springframework.stereotype.Component
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomOptionsModel
import site.qgames.game.model.room.RoomSettingsUpdateModel
import site.qgames.game.service.game.GameRegistry

/**
 * Проверяет настройки комнаты: room — по лимиту наблюдателей ([RoomOptionsModel.MAX_SPECTATORS]), simple и advanced —
 * по правилам игры (часы advanced — по [site.qgames.games.core.TimeControlRules], остальное — модулем игры). Ошибки
 * отдаются по полям в формате `@Valid` (400 VALIDATION_ERROR, `errors`).
 */
@Component
class RoomSettingsValidator(private val gameRegistry: GameRegistry) {

    /**
     * Замена групп настроек: нужна хотя бы одна группа, simple и advanced не вместе. Поля — по пути в теле запроса
     * (`room.maxSpectators`, `simple.variant`, `advanced.timeControl.baseSeconds`).
     */
    fun checkUpdate(gameCode: GameCode, update: RoomSettingsUpdateModel) {
        val errors = linkedMapOf<String, String>()
        if (update.room == null && update.simple == null && update.advanced == null) {
            errors[ROOM] = "at least one of room, simple and advanced is required"
        }
        if (update.simple != null && update.advanced != null) {
            errors[ADVANCED] = "must not be set together with simple"
        }
        update.room?.let { room ->
            if (room.maxSpectators !in RoomOptionsModel.MAX_SPECTATORS) {
                errors["$ROOM.maxSpectators"] =
                    "must be between ${RoomOptionsModel.MAX_SPECTATORS.first} and ${RoomOptionsModel.MAX_SPECTATORS.last}"
            }
        }
        update.simple?.let { errors += prefixed("$SIMPLE.", validateSimple(gameCode, it)) }
        update.advanced?.let { errors += prefixed("$ADVANCED.", validateAdvanced(gameCode, it)) }
        if (errors.isNotEmpty()) {
            throw RoomErrors.invalidSettings(errors)
        }
    }

    /** Simple-настройки сами по себе (перевод в advanced): поля — относительно настроек (`variant`). */
    fun checkSimple(gameCode: GameCode, simple: Any) {
        val errors = validateSimple(gameCode, simple)
        if (errors.isNotEmpty()) {
            throw RoomErrors.invalidSettings(errors)
        }
    }

    private fun validateSimple(gameCode: GameCode, simple: Any): Map<String, String> {
        val rules = gameRegistry.rules(gameCode)
        if (!rules.simpleClass.isInstance(simple)) return mapOf(GAME_CODE to GAME_MISMATCH)
        return rules.validateSimple(simple).associate { it.field to it.message }
    }

    private fun validateAdvanced(gameCode: GameCode, advanced: Any): Map<String, String> {
        val rules = gameRegistry.rules(gameCode)
        if (!rules.advancedClass.isInstance(advanced)) return mapOf(GAME_CODE to GAME_MISMATCH)
        val clock = rules.timeControl.validate(rules.clock(advanced)).map { "$TIME_CONTROL.${it.field}" to it.message }
        return (clock + rules.validateAdvanced(advanced).map { it.field to it.message }).toMap()
    }

    private fun prefixed(prefix: String, errors: Map<String, String>) = errors.mapKeys { prefix + it.key }

    private companion object {
        const val ROOM = "room"
        const val SIMPLE = "simple"
        const val ADVANCED = "advanced"
        const val TIME_CONTROL = "timeControl"
        const val GAME_CODE = "gameCode"
        const val GAME_MISMATCH = "must match the game of the room"
    }
}
