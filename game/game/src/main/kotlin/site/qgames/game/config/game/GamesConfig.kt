package site.qgames.game.config.game

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import site.qgames.games.api.session.SessionCodec
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.checkers.CheckersGameRules
import site.qgames.games.nothing.NothingGameRules

/**
 * Подключённые игры: по бину на каждый модуль games/<игра>. Код игры (GameRules.code) должен быть в enum GameCode и
 * в таблице game. Платформа партий ([SessionEngine]) общая для всех игр.
 */
@Configuration
class GamesConfig {

    @Bean
    fun nothingGameRules() = NothingGameRules()

    @Bean
    fun checkersGameRules() = CheckersGameRules()

    @Bean
    fun sessionEngine(sessionCodec: SessionCodec) = SessionEngine(sessionCodec)
}
