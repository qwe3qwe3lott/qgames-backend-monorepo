package site.qgames.game

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class GameApplication

fun main(args: Array<String>) {
    runApplication<GameApplication>(*args)
}
