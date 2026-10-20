package site.qgames.game.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("qgames.game")
data class GameProperties(
    val catalog: Catalog,
    val room: Room,
    val session: Session,
) {
    data class Catalog(
        /** Как часто ForceDisabledGameCleanupJob ищет комнаты принудительно отключённых игр. */
        val forceDisabledCleanupInterval: Duration,
        /** Запуск ForceDisabledGameCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val forceDisabledCleanupEnabled: Boolean = true,
        /** Сколько комнат удаляется за один запуск, остальные — в следующих. */
        val forceDisabledCleanupBatchSize: Int = 100,
    )

    data class Room(
        /** Комната без игроков и наблюдателей удаляется, когда пустует дольше этого времени. */
        val emptyTtl: Duration,
        val cleanupInterval: Duration,
        /** Запуск EmptyRoomCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val cleanupEnabled: Boolean = true,
        /** Сколько вышедший участник ещё числится в комнате (кроме игроков идущей партии: им срок задаёт игра). */
        val leaveGrace: LeaveGrace,
        /** Как часто RoomLeaveJob ищет участников с истёкшим сроком выхода. */
        val leaveCheckInterval: Duration,
        /** Запуск RoomLeaveJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val leaveEnabled: Boolean = true,
        /** Сколько комнат с истёкшими сроками выхода обрабатывается за один запуск, остальные — в следующих. */
        val leaveBatchSize: Int = 100,
        /** Подключения к потоку событий комнаты (SSE): открытый поток и есть нахождение в комнате. */
        val connection: Connection,
    )

    data class Connection(
        /** Срок аренды подключения: инстанс, держащий поток, продлевает его каждые [heartbeatInterval]. */
        val lease: Duration,
        /** Как часто слать в потоки комментарий `: ping` и продлевать аренду подключений (RoomConnectionHeartbeatJob). */
        val heartbeatInterval: Duration,
        /** Запуск RoomConnectionHeartbeatJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val heartbeatEnabled: Boolean = true,
        /** Как часто удалять подключения с истёкшей арендой — подключения упавших инстансов (RoomConnectionCleanupJob). */
        val cleanupInterval: Duration,
        /** Запуск RoomConnectionCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val cleanupEnabled: Boolean = true,
        /** Сколько комнат с протухшими подключениями обрабатывается за один запуск, остальные — в следующих. */
        val cleanupBatchSize: Int = 100,
    )

    data class LeaveGrace(
        /** Владелец вне игры: комната ждёт или он не игрок идущей партии. */
        val owner: Duration,
        /** Остальные участники вне игры и наблюдатели. */
        val member: Duration,
    )

    data class Session(
        /** Как часто MoveTimeoutJob ищет партии с истёкшим временем хода. */
        val timeoutCheckInterval: Duration,
        /** Запуск MoveTimeoutJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val timeoutEnabled: Boolean = true,
        /** Сколько партий с истёкшим временем обрабатывается за один запуск, остальные — в следующих. */
        val timeoutBatchSize: Int = 100,
    )
}
