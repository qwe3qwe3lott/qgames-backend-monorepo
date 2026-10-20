package site.qgames.game.service.game

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameDetailsModel
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.game.GameRulesModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.repository.game.GameRepository

/**
 * Каталог игр и доступность игры: описание и статус публикации — из таблицы game, параметры правил — из модуля игры
 * ([GameRegistry]). Проверки доступности вызываются комнатами и партиями внутри их транзакций.
 */
@Service
class GameService(
    private val gameRepository: GameRepository,
    private val gameRegistry: GameRegistry,
) {

    /**
     * Игры каталога в порядке показа, без скрытых (HIDDEN), неопубликованных (NOT_PUBLISHED) и принудительно
     * отключённых (FORCE_DISABLED).
     */
    @Transactional(readOnly = true)
    fun list(): List<GameModel> = gameRepository.findAll().filter { it.publishStatus.listed }

    /** Игра с параметрами правил. Скрытая игра отдаётся, неопубликованная и принудительно отключённая — 404. */
    @Transactional(readOnly = true)
    fun details(code: GameCode): GameDetailsModel {
        val game = accessible(code)
        return GameDetailsModel(game = game, rules = rules(code))
    }

    /**
     * Игра доступна для новых комнат и партий: опубликована или скрыта. Неопубликованная и принудительно
     * отключённая — 404 GAME_NOT_FOUND, временно недоступная — 409 GAME_UNAVAILABLE.
     */
    fun checkPlayable(code: GameCode) {
        val game = accessible(code)
        if (!game.publishStatus.playable) {
            throw RoomErrors.gameUnavailable()
        }
    }

    /**
     * С комнатами и партиями игры можно работать, если игра видна наружу: не NOT_PUBLISHED и не FORCE_DISABLED
     * (иначе 404 GAME_NOT_FOUND).
     */
    fun checkAccessible(code: GameCode) {
        accessible(code)
    }

    fun isForceDisabled(code: GameCode): Boolean =
        gameRepository.findByCode(code)?.publishStatus == GamePublishStatus.FORCE_DISABLED

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
            // Порядок — как в enum, чтобы ответ был стабильным.
            timeControlModes = RoomTimeControlMode.entries.filter { mode -> timeControl.modes.any { it.name == mode.name } },
            moveSeconds = timeControl.moveSeconds,
            settingsLimits = rules.settingsLimits,
            defaultSettings = RoomSettingsModel(
                timeControl = RoomTimeControlModel(
                    mode = RoomTimeControlMode.valueOf(timeControl.default.mode.name),
                    moveSeconds = timeControl.default.moveSeconds,
                ),
                gameSettings = rules.defaultSettings,
            ),
        )
    }
}
