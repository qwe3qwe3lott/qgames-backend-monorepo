package site.qgames.game.job.room

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomConnectionRepository
import site.qgames.game.service.room.RoomService
import java.time.Clock

/**
 * Удаляет подключения к потокам комнат с истёкшей арендой — их инстанс упал или потерял связь с БД — и уводит
 * пользователей, у которых не осталось подключений, как при закрытии потока. Работает на всех инстансах без ShedLock:
 * каждую комнату обрабатывает тот, кто первым её заблокировал (FOR UPDATE SKIP LOCKED), каждая — в своей транзакции.
 * По расписанию — только при qgames.game.room.connection.cleanup-enabled.
 */
@Component
class RoomConnectionCleanupJob(
    private val roomConnectionRepository: RoomConnectionRepository,
    private val roomService: RoomService,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.room.connection.cleanup-interval}")
    fun scheduledRun() {
        if (properties.room.connection.cleanupEnabled) {
            run()
        }
    }

    /** Возвращает число удалённых подключений. */
    fun run(): Int {
        val rooms = roomConnectionRepository.findRoomIdsWithExpired(clock.instant(), properties.room.connection.cleanupBatchSize)
        val removed = rooms.sumOf { roomId ->
            try {
                roomService.removeExpiredConnections(roomId)
            } catch (e: RuntimeException) {
                log.error("Failed to remove expired connections of room {}", roomId, e)
                0
            }
        }
        if (removed > 0) {
            log.info("Removed {} room connections with expired lease", removed)
        }
        return removed
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomConnectionCleanupJob::class.java)
    }
}
