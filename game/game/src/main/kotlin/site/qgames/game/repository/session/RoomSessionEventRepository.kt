package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.session.RoomSessionEntityMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.session.RoomSessionEventModel
import site.qgames.games.api.session.SessionEvent
import java.time.Instant
import java.util.UUID

/** Журнал событий партии: только дописывается. */
@Repository
class RoomSessionEventRepository(
    private val roomSessionEventPostgresqlRepository: RoomSessionEventPostgresqlRepository,
    private val roomSessionEntityMapper: RoomSessionEntityMapper,
) {

    /** Дописывает [events] с seq от [fromSeq] подряд; все — с моментом записи [now]. */
    fun append(sessionId: UUID, fromSeq: Long, events: List<SessionEvent>, now: Instant) {
        events.forEachIndexed { index, event ->
            roomSessionEventPostgresqlRepository.insert(roomSessionEntityMapper.toEntity(sessionId, fromSeq + index, event, now))
        }
    }

    /** События партии игры [gameCode] с seq от [fromSeq] до [toSeq] включительно, по порядку. */
    fun findRange(sessionId: UUID, gameCode: GameCode, fromSeq: Long, toSeq: Long): List<RoomSessionEventModel> =
        if (fromSeq > toSeq) {
            emptyList()
        } else {
            roomSessionEventPostgresqlRepository.findRange(sessionId, fromSeq, toSeq)
                .map { roomSessionEntityMapper.toModel(it, gameCode) }
        }
}
