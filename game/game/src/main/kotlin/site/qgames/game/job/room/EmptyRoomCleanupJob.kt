package site.qgames.game.job.room

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.service.room.RoomService
import java.time.Clock

/**
 * Удаляет комнаты, в которых нет присутствующих участников дольше qgames.game.room.empty-ttl. Отсутствующие игроки
 * идущей партии комнату не держат: их партия отменяется. Запускается на всех инстансах, выполняется одним (ShedLock).
 * Комнаты выбираются без блокировки, затем каждая блокируется (FOR UPDATE SKIP LOCKED) с перепроверкой в своей
 * транзакции. По расписанию — только при qgames.game.room.cleanup-enabled.
 */
@Component
class EmptyRoomCleanupJob(
    private val roomRepository: RoomRepository,
    private val roomService: RoomService,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.room.cleanup-interval}")
    @SchedulerLock(name = "empty-room-cleanup", lockAtMostFor = "PT5M")
    fun scheduledRun() {
        if (properties.room.cleanupEnabled) {
            run()
        }
    }

    /** Возвращает число удалённых комнат. Заблокированные другими запросами комнаты удалятся в следующий запуск. */
    fun run(): Int {
        val threshold = clock.instant().minus(properties.room.emptyTtl)
        val deleted = roomRepository.findIdsEmptySince(threshold, properties.room.cleanupBatchSize).count { roomId ->
            try {
                roomService.deleteIfEmpty(roomId, threshold)
            } catch (e: RuntimeException) {
                log.error("Failed to delete empty room {}", roomId, e)
                false
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} empty rooms", deleted)
        }
        return deleted
    }

    companion object {
        private val log = LoggerFactory.getLogger(EmptyRoomCleanupJob::class.java)
    }
}
