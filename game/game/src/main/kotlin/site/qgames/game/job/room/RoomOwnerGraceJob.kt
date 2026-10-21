package site.qgames.game.job.room

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.service.room.RoomService
import java.time.Clock

/**
 * Закрывает истёкшие окна владения: ушедший владелец не вернулся за qgames.game.room.owner-grace, и владение получает
 * присутствующий участник (самый ранний игрок, затем наблюдатель), а если присутствующих нет — первый вошедший.
 * Работает на всех инстансах без ShedLock: комнаты выбираются без блокировки, а каждую обрабатывает тот, кто первым
 * её заблокировал (FOR UPDATE SKIP LOCKED), с перепроверкой в своей транзакции: ошибка одной не мешает остальным.
 * Комнаты неопубликованных игр пропускаются. По расписанию — только при qgames.game.room.owner-grace-enabled.
 */
@Component
class RoomOwnerGraceJob(
    private val roomRepository: RoomRepository,
    private val roomService: RoomService,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.room.owner-grace-check-interval}")
    fun scheduledRun() {
        if (properties.room.ownerGraceEnabled) {
            run()
        }
    }

    /** Возвращает число комнат, где окно владения закрыто. */
    fun run(): Int {
        val rooms = roomRepository.findIdsWithExpiredOwnerGrace(clock.instant(), properties.room.ownerGraceBatchSize)
        return rooms.count { roomId ->
            try {
                roomService.endOwnerGrace(roomId)
            } catch (e: RuntimeException) {
                log.error("Failed to end the owner grace of room {}", roomId, e)
                false
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomOwnerGraceJob::class.java)
    }
}
