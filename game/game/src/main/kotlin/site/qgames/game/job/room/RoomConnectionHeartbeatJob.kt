package site.qgames.game.job.room

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomConnectionRepository
import site.qgames.game.stream.room.RoomStreamRegistry
import site.qgames.game.stream.session.SessionStreamRegistry
import java.time.Clock

/**
 * Пульс потоков комнат и партий: шлёт в каждый поток комментарий `: ping` (держит соединение через прокси и
 * выявляет отвалившихся клиентов — запись в них падает) и одним запросом продлевает аренду подключений потоков комнат
 * (у потоков партий аренды нет).
 * Продлеваются только подключения живых потоков,
 * поэтому «осиротевшие» строки (отключение не удалось) истекут и будут удалены RoomConnectionCleanupJob.
 * Если же у живого потока строки подключения уже нет (продление задержалось дольше аренды, и подключение удалил
 * cleanup), поток закрывается: пользователь для комнаты уже ушёл, а клиент переподключится и войдёт заново.
 * По расписанию — только при qgames.game.room.connection.heartbeat-enabled.
 */
@Component
class RoomConnectionHeartbeatJob(
    private val registry: RoomStreamRegistry,
    private val sessionRegistry: SessionStreamRegistry,
    private val roomConnectionRepository: RoomConnectionRepository,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.room.connection.heartbeat-interval}")
    fun scheduledRun() {
        if (properties.room.connection.heartbeatEnabled) {
            run()
        }
    }

    /** Возвращает число продлённых подключений. */
    fun run(): Int {
        val subscribers = registry.all()
        subscribers.forEach { it.ping() }
        sessionRegistry.all().forEach { it.ping() }
        val connected = subscribers.filter { !it.isClosed && it.connectionId != null }
        if (connected.isEmpty()) return 0
        val renewed = roomConnectionRepository.renew(connected.map { it.connectionId!! }, clock.instant() + properties.room.connection.lease)
        val lost = connected.filter { it.connectionId !in renewed }
        if (lost.isNotEmpty()) {
            log.warn("Closing {} room streams whose connections are gone", lost.size)
            lost.forEach { it.close() }
        }
        return renewed.size
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomConnectionHeartbeatJob::class.java)
    }
}
