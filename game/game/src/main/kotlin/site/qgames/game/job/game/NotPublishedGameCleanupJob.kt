package site.qgames.game.job.game

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.service.room.RoomService

/**
 * Убирает комнаты неопубликованных игр (NOT_PUBLISHED): отменяет идущие партии и удаляет комнаты. До его
 * срабатывания ручки таких комнат отвечают 404 GAME_NOT_FOUND. Запускается на всех инстансах, выполняется одним
 * (ShedLock); комнаты блокируются так же, как в остальных джобах (FOR UPDATE SKIP LOCKED), каждая — в своей
 * транзакции. По расписанию — только при qgames.game.catalog.not-published-cleanup-enabled.
 */
@Component
class NotPublishedGameCleanupJob(
    private val roomRepository: RoomRepository,
    private val roomService: RoomService,
    private val properties: GameProperties,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.catalog.not-published-cleanup-interval}")
    @SchedulerLock(name = "not-published-game-cleanup", lockAtMostFor = "PT5M")
    fun scheduledRun() {
        if (properties.catalog.notPublishedCleanupEnabled) {
            run()
        }
    }

    /** Возвращает число удалённых комнат. Заблокированные другими запросами комнаты удалятся в следующий запуск. */
    fun run(): Int {
        val rooms = roomRepository.findIdsByGamePublishStatus(
            GamePublishStatus.NOT_PUBLISHED,
            properties.catalog.notPublishedCleanupBatchSize,
        )
        val deleted = rooms.count { roomId ->
            try {
                roomService.deleteOfNotPublishedGame(roomId)
            } catch (e: RuntimeException) {
                log.error("Failed to delete room {} of a not published game", roomId, e)
                false
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} rooms of not published games", deleted)
        }
        return deleted
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotPublishedGameCleanupJob::class.java)
    }
}
