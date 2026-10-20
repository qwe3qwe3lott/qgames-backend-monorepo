package site.qgames.game.service.room

import org.springframework.stereotype.Component
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.api.TimeControlMode

/**
 * Проверяет настройки комнаты по правилам игры: режим времени и его параметры — по [site.qgames.games.api.TimeControlRules],
 * настройки игры — правилами модуля игры. Ошибки отдаются по полям в формате `@Valid` (400 VALIDATION_ERROR, `errors`).
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

    /** Нарушения по полям относительно настроек комнаты (`timeControl.moveSeconds`, `gameSettings.rounds`). */
    fun validate(gameCode: GameCode, settings: RoomSettingsModel): Map<String, String> {
        val rules = gameRegistry.rules(gameCode)
        val errors = linkedMapOf<String, String>()

        val timeControl = settings.timeControl
        val limits = rules.timeControl
        val mode = TimeControlMode.valueOf(timeControl.mode.name)
        if (mode !in limits.modes) {
            errors["timeControl.mode"] = "is not supported by the game, allowed: ${limits.modes.joinToString()}"
        } else {
            when (mode) {
                TimeControlMode.NONE -> if (timeControl.moveSeconds != null) {
                    errors["timeControl.moveSeconds"] = "must be null when there is no timer"
                }
                TimeControlMode.PER_MOVE -> if (timeControl.moveSeconds == null || timeControl.moveSeconds !in limits.moveSeconds) {
                    errors["timeControl.moveSeconds"] =
                        "must be between ${limits.moveSeconds.first} and ${limits.moveSeconds.last}"
                }
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
