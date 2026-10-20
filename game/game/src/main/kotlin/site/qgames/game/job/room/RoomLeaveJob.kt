package site.qgames.game.job.room

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.service.room.RoomService
import java.time.Clock

/**
 * Завершает отложенный выход: удаляет из комнат участников, чей срок выхода истёк (игроки партии на паузе ждут).
 * Работает на всех инстансах без ShedLock: каждую комнату обрабатывает тот, кто первым её заблокировал
 * (FOR UPDATE SKIP LOCKED), остальные её пропускают. Каждая комната — в своей транзакции, ошибка одной не мешает
 * остальным. По расписанию — только при qgames.game.room.leave-enabled.
 */
@Component
class RoomLeaveJob(
    private val roomMemberRepository: RoomMemberRepository,
    private val roomService: RoomService,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.room.leave-check-interval}")
    fun scheduledRun() {
        if (properties.room.leaveEnabled) {
            run()
        }
    }

    /** Возвращает число удалённых участников. */
    fun run(): Int {
        val rooms = roomMemberRepository.findRoomIdsWithExpiredLeave(clock.instant(), properties.room.leaveBatchSize)
        return rooms.sumOf { roomId ->
            try {
                roomService.removeExpiredMembers(roomId)
            } catch (e: RuntimeException) {
                log.error("Failed to remove members with expired leave deadline from room {}", roomId, e)
                0
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomLeaveJob::class.java)
    }
}
