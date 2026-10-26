package site.qgames.game.service.room

import org.springframework.stereotype.Component
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.service.game.GameRegistry

/**
 * Проверяет настройки комнаты по правилам игры: шахматные часы — по [site.qgames.games.core.TimeControlRules] (можно ли
 * без таймера, границы запаса и добавки), настройки игры — правилами модуля игры. Ошибки отдаются по полям в формате
 * `@Valid` (400 VALIDATION_ERROR, `errors`).
 */
@Component
class RoomSettingsValidator(private val gameRegistry: GameRegistry) {

    /**
     * [fieldPrefix] — путь к настройкам в теле запроса (`settings.` при создании комнаты, пусто при замене настроек),
     * чтобы клиент подсветил нужное поле формы.
     */
    fun check(gameCode: GameCode, settings: RoomSettingsModel, fieldPrefix: String) {
        val errors = validate(gameCode, settings)
        if (errors.isNotEmpty()) {
            throw RoomErrors.invalidSettings(errors.mapKeys { fieldPrefix + it.key })
        }
    }

    /** Нарушения по полям относительно настроек комнаты (`timeControl.baseSeconds`, `gameSettings.rounds`). */
    fun validate(gameCode: GameCode, settings: RoomSettingsModel): Map<String, String> {
        val rules = gameRegistry.rules(gameCode)
        val errors = linkedMapOf<String, String>()

        val timeControl = settings.timeControl
        val limits = rules.timeControl
        if (timeControl == null) {
            if (!limits.optional) errors["timeControl"] = "is required: the game is played only with a clock"
        } else {
            if (timeControl.baseSeconds !in limits.baseSeconds) {
                errors["timeControl.baseSeconds"] = "must be between ${limits.baseSeconds.first} and ${limits.baseSeconds.last}"
            }
            if (timeControl.incrementSeconds !in limits.incrementSeconds) {
                errors["timeControl.incrementSeconds"] =
                    "must be between ${limits.incrementSeconds.first} and ${limits.incrementSeconds.last}"
            }
        }

        if (!rules.settingsClass.isInstance(settings.gameSettings)) {
            errors["gameSettings.gameCode"] = "must match the game of the room"
        } else {
            rules.validateSettings(settings.gameSettings).forEach { errors["gameSettings.${it.field}"] = it.message }
        }
        return errors
    }
}
