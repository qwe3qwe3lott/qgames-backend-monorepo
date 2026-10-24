package site.qgames.game.job.session

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.session.RoomSessionService
import java.time.Clock

/**
 * Обрабатывает истёкшее время хода. Работает на всех инстансах: каждую партию обрабатывает тот, кто
 * первым заблокировал её комнату (FOR UPDATE SKIP LOCKED), остальные её пропускают. Каждая партия — в своей
 * транзакции, ошибка одной не мешает остальным. По расписанию — только при qgames.game.session.timeout-enabled.
 */
@Component
class MoveTimeoutJob(
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionService: RoomSessionService,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${qgames.game.session.timeout-check-interval}")
    fun scheduledRun() {
        if (properties.session.timeoutEnabled) {
            run()
        }
    }

    /** Возвращает число партий, в которых обработано истечение времени. */
    fun run(): Int {
        val expired = roomSessionRepository.findIdsWithExpiredMove(clock.instant(), properties.session.timeoutBatchSize)
        return expired.count { sessionId ->
            try {
                roomSessionService.timeout(sessionId)
            } catch (e: RuntimeException) {
                log.error("Failed to process move timeout in session {}", sessionId, e)
                false
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MoveTimeoutJob::class.java)
    }
}
