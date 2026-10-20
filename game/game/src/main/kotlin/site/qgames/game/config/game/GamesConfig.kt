package site.qgames.game.config.game

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import site.qgames.games.nothing.NothingGameRules

/**
 * Подключённые игры: по бину на каждый модуль games/<игра>. Код игры (GameRules.code) должен быть в enum GameCode и
 * в таблице game.
 */
@Configuration
class GamesConfig {

    @Bean
    fun nothingGameRules() = NothingGameRules()
}
