package site.qgames.game.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("qgames.game")
data class GameProperties(
    val room: Room,
) {
    data class Room(
        /** Комната без игроков и наблюдателей удаляется, когда пустует дольше этого времени. */
        val emptyTtl: Duration,
        val cleanupInterval: Duration,
        /** В тестах выключено: job вызывается напрямую. */
        val cleanupEnabled: Boolean = true,
    )
}
