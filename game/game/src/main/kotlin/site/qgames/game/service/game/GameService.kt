package site.qgames.game.service.game

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameDetailsModel
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.game.GameRulesModel
import site.qgames.game.model.room.RoomOptionsModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.repository.game.GameRepository
import site.qgames.game.service.room.RoomSettingsValidator

/**
 * Каталог игр и доступность игры: описание и статус публикации — из таблицы game, параметры правил — из модуля игры
 * ([GameRegistry]). Проверки доступности вызываются комнатами и партиями внутри их транзакций.
 */
@Service
class GameService(
    private val gameRepository: GameRepository,
    private val gameRegistry: GameRegistry,
    private val roomSettingsValidator: RoomSettingsValidator,
) {

    /** Игры каталога в порядке показа, без скрытых (HIDDEN) и неопубликованных (NOT_PUBLISHED). */
    @Transactional(readOnly = true)
    fun list(): List<GameModel> = gameRepository.findAll().filter { it.publishStatus.listed }

    /** Игра с параметрами правил. Скрытая игра отдаётся, неопубликованная — 404 GAME_NOT_FOUND. */
    @Transactional(readOnly = true)
    fun details(code: GameCode): GameDetailsModel {
        val game = accessible(code)
        return GameDetailsModel(game = game, rules = rules(code))
    }

    /**
     * Advanced-настройки, выведенные из simple-настроек [simple] (класс модуля игры). Игра должна быть доступна;
     * ошибки simple-настроек — по полям тела (`variant`).
     */
    @Transactional(readOnly = true)
    fun toAdvanced(code: GameCode, simple: Any): Any {
        accessible(code)
        roomSettingsValidator.checkSimple(code, simple)
        return gameRegistry.rules(code).toAdvanced(simple)
    }

    /** Настройки новой комнаты: simple по умолчанию и выведенный из них advanced. */
    fun defaultSettings(code: GameCode): RoomSettingsModel {
        val rules = gameRegistry.rules(code)
        return RoomSettingsModel(room = RoomOptionsModel.DEFAULT, simple = rules.defaultSimple, advanced = rules.toAdvanced(rules.defaultSimple))
    }

    /**
     * С комнатами и партиями игры можно работать (в том числе создавать комнаты и начинать партии), если игра видна
     * наружу: опубликована или скрыта. Неопубликованная (NOT_PUBLISHED) — 404 GAME_NOT_FOUND.
     */
    fun checkAccessible(code: GameCode) {
        accessible(code)
    }

    fun isNotPublished(code: GameCode): Boolean =
        gameRepository.findByCode(code)?.publishStatus == GamePublishStatus.NOT_PUBLISHED

    private fun accessible(code: GameCode): GameModel {
        val game = gameRepository.findByCode(code)
        // Без модуля правил игра наружу не выдаётся, даже если статус по ошибке это разрешает: правила обязательны
        // только для видимых статусов, а статус позже будет менять админка.
        if (game == null || !game.publishStatus.exposed || gameRegistry.rulesOrNull(code) == null) {
            throw RoomErrors.gameNotFound()
        }
        return game
    }

    private fun rules(code: GameCode): GameRulesModel {
        val rules = gameRegistry.rules(code)
        val timeControl = rules.timeControl
        return GameRulesModel(
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            spectators = RoomOptionsModel.MAX_SPECTATORS,
            timeControlOptional = timeControl.optional,
            baseSeconds = timeControl.baseSeconds,
            bonusSeconds = timeControl.bonusSeconds,
            settingsLimits = rules.settingsLimits,
            defaultSettings = defaultSettings(code),
        )
    }
}
