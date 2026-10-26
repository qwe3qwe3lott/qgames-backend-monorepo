package site.qgames.game.repository.session

import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository
import site.qgames.game.model.session.RoomSessionEventModel
import site.qgames.games.core.SessionEvent

/** Журнал событий партии: только дописывается. */
@Repository
class RoomSessionEventRepository(
    private val roomSessionEventExposedRepository: RoomSessionEventExposedRepository,
) {

    /** Дописывает [events] с seq от [fromSeq] подряд; все — с моментом записи [now]. */
    fun append(sessionId: UUID, fromSeq: Long, events: List<SessionEvent>, now: Instant) {
        events.forEachIndexed { index, event -> roomSessionEventExposedRepository.insert(sessionId, fromSeq + index, event, now) }
    }

    /** События партии с seq от [fromSeq] до [toSeq] включительно, по порядку. */
    fun findRange(sessionId: UUID, fromSeq: Long, toSeq: Long): List<RoomSessionEventModel> =
        if (fromSeq > toSeq) emptyList() else roomSessionEventExposedRepository.findRange(sessionId, fromSeq, toSeq)
}
