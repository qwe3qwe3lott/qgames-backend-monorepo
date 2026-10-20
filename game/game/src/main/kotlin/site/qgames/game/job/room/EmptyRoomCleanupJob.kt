package site.qgames.game.job.room

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomRepository
import java.time.Clock

/**
 * Удаляет комнаты, в которых нет ни игроков, ни наблюдателей дольше qgames.game.room.empty-ttl.
 * Запускается на всех инстансах, выполняется одним (ShedLock). По расписанию — только при
 * qgames.game.room.cleanup-enabled.
 */
@Component
class EmptyRoomCleanupJob(
    private val roomRepository: RoomRepository,
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

    fun run(): Int {
        val deleted = roomRepository.deleteEmptySince(clock.instant().minus(properties.room.emptyTtl))
        if (deleted > 0) {
            log.info("Deleted {} empty rooms", deleted)
        }
        return deleted
    }

    companion object {
        private val log = LoggerFactory.getLogger(EmptyRoomCleanupJob::class.java)
    }
}
