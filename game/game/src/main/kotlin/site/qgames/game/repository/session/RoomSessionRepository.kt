package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.util.newId
import java.time.Instant
import java.util.UUID

@Repository
class RoomSessionRepository(
    private val roomSessionExposedRepository: RoomSessionExposedRepository,
) {

    /**
     * Новая партия ([RoomSessionStatus.IN_PROGRESS]) с пустым журналом; места игроков — по порядку в [players].
     * [timeControl] и [gameSettings] (JSON) — снимок настроек комнаты на момент старта.
     */
    fun insert(
        roomId: UUID,
        gameCode: GameCode,
        seed: Long,
        timeControl: RoomTimeControlModel?,
        gameSettings: String,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionModel = roomSessionExposedRepository.insert(
        RoomSessionModel(
            id = newId(),
            roomId = roomId,
            gameCode = gameCode,
            status = RoomSessionStatus.IN_PROGRESS,
            seed = seed,
            lastSeq = 0,
            timeControl = timeControl,
            gameSettings = gameSettings,
            moveDeadline = null,
            createdAt = now,
            finishedAt = null,
        ),
        players,
    )

    fun findById(id: UUID): RoomSessionModel? = roomSessionExposedRepository.findById(id)

    /** Последняя партия комнаты (для показа результата после завершения или отмены). */
    fun findLatest(roomId: UUID): RoomSessionModel? = roomSessionExposedRepository.findLatest(roomId)

    /** Игроки партии в порядке мест. */
    fun findPlayers(sessionId: UUID): List<UUID> = roomSessionExposedRepository.findPlayers(sessionId)

    /**
     * Идущие партии с идущим таймером, у которых время хода истекло к [now], — самые просроченные первыми. Партии
     * неопубликованных игр (NOT_PUBLISHED) пропускаются. Без блокировок.
     */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> = roomSessionExposedRepository.findIdsWithExpiredMove(now, limit)

    /** После записи событий журнала: последний seq [lastSeq], статус и проекция таймера [moveDeadline]. */
    fun advance(id: UUID, lastSeq: Long, status: RoomSessionStatus, moveDeadline: Instant?, finishedAt: Instant?) =
        roomSessionExposedRepository.advance(id, lastSeq, status, moveDeadline, finishedAt)
}
