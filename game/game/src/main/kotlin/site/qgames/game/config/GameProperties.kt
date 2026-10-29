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
        /** Как часто NotPublishedGameCleanupJob ищет комнаты неопубликованных игр. */
        val notPublishedCleanupInterval: Duration,
        /** Запуск NotPublishedGameCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val notPublishedCleanupEnabled: Boolean = true,
        /** Сколько комнат удаляется за один запуск, остальные — в следующих. */
        val notPublishedCleanupBatchSize: Int = 100,
    )

    data class Room(
        /**
         * Комната без присутствующих участников удаляется, когда пустует дольше этого времени. Идущая партия, в которой
         * остались только отсутствующие игроки, при этом отменяется.
         */
        val emptyTtl: Duration,
        val cleanupInterval: Duration,
        /** Запуск EmptyRoomCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val cleanupEnabled: Boolean = true,
        /** Сколько пустых комнат удаляется за один запуск, остальные — в следующих. */
        val cleanupBatchSize: Int = 100,
        /** Подключения к потоку событий комнаты (SSE): открытый поток и есть нахождение в комнате. */
        val connection: Connection,
    )

    data class Connection(
        /** Срок аренды подключения: приложение продлевает аренду живых потоков каждые [heartbeatInterval]. */
        val lease: Duration,
        /** Как часто слать в потоки комментарий `: ping` и продлевать аренду подключений (RoomConnectionHeartbeatJob). */
        val heartbeatInterval: Duration,
        /** Запуск RoomConnectionHeartbeatJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val heartbeatEnabled: Boolean = true,
        /** Как часто удалять подключения с истёкшей арендой — их никто не продлевает (RoomConnectionCleanupJob). */
        val cleanupInterval: Duration,
        /** Запуск RoomConnectionCleanupJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val cleanupEnabled: Boolean = true,
        /** Сколько комнат с протухшими подключениями обрабатывается за один запуск, остальные — в следующих. */
        val cleanupBatchSize: Int = 100,
    )

    data class Session(
        /** Как часто MoveTimeoutJob ищет партии с истёкшим временем хода. */
        val timeoutCheckInterval: Duration,
        /** Запуск MoveTimeoutJob по расписанию. В тестах выключен: job вызывается напрямую. */
        val timeoutEnabled: Boolean = true,
        /** Сколько партий с истёкшим временем обрабатывается за один запуск, остальные — в следующих. */
        val timeoutBatchSize: Int = 100,
        /** Кеш состояний партий по проекциям зрителей (SessionStateCache). */
        val stateCache: StateCache = StateCache(),
        /**
         * Seed партии из заголовка `X-Qgames-Seed` ручки старта — для воспроизводимых e2e-сценариев. Включается только
         * на локальном стенде (профиль local); выключен — заголовок игнорируется.
         */
        val seedHeaderEnabled: Boolean = false,
    )

    data class StateCache(
        /** Сколько состояний (партия × проекция) держит кеш. */
        val maxSize: Long = 2_000,
        /** Состояние, которое так долго не читали, вытесняется. */
        val expireAfterAccess: Duration = Duration.ofMinutes(5),
    )
}
