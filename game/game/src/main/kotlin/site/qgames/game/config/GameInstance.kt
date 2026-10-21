package site.qgames.game.config

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Идентификатор процесса game, случайный при каждом старте. Им помечаются подключения к потокам комнат
 * (room_connection.instance_id), которые держит этот процесс.
 */
@Component
class GameInstance {

    val id: UUID = UUID.randomUUID()
}
