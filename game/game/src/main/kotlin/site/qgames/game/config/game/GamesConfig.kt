package site.qgames.game.config.game

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import site.qgames.games.nothing.NothingGameRules

/**
 * Подключённые игры: по бину на каждый модуль games/<игра>. Тип игры должен совпадать с значением GameType в контракте.
 */
@Configuration
class GamesConfig {

    @Bean
    fun nothingGameRules() = NothingGameRules()
}
