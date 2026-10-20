package site.qgames.game.service.game

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.repository.game.GameRepository

/**
 * Сверяет каталог игр в БД с кодом приложения при старте (после миграций): набор кодов в таблице game совпадает
 * с [GameCode], а у каждой игры, кроме неопубликованных (NOT_PUBLISHED), есть модуль правил. При расхождении сервис не
 * стартует.
 */
@Component
class GameCatalogVerifier(
    private val gameRepository: GameRepository,
    private val gameRegistry: GameRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val tableCodes = gameRepository.findAllCodes().toSet()
        val enumCodes = GameCode.entries.map { it.code }.toSet()
        check(tableCodes == enumCodes) {
            "Game catalog does not match GameCode: only in the game table ${tableCodes - enumCodes}, " +
                "only in GameCode ${enumCodes - tableCodes}"
        }
        val withoutRules = gameRepository.findAll()
            .filter { it.publishStatus != GamePublishStatus.NOT_PUBLISHED && gameRegistry.rulesOrNull(it.code) == null }
            .map { it.code }
        check(withoutRules.isEmpty()) { "No rules registered for games $withoutRules, only NOT_PUBLISHED games may have no rules" }
    }
}
