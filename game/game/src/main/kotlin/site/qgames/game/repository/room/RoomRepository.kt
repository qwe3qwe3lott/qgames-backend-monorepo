package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomOwnerGraceModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.util.newId
import java.time.Instant
import java.util.UUID

@Repository
class RoomRepository(
    private val roomExposedRepository: RoomExposedRepository,
) {

    /**
     * Новая комната ждёт игроков ([RoomStatus.WAITING]). [timeControl] — часы (null — без таймера), [gameSettings] — JSON
     * настроек игры. Участников и владельца у неё нет, она пустая с [now], а окно владения до [ownerGraceDeadline] — за
     * создателем [creatorId].
     */
    fun insert(
        gameCode: GameCode,
        creatorId: UUID,
        ownerGraceDeadline: Instant,
        timeControl: RoomTimeControlModel?,
        gameSettings: String,
        now: Instant,
    ): RoomModel = roomExposedRepository.insert(
        RoomModel(
            id = newId(),
            gameCode = gameCode,
            ownerId = null,
            ownerGrace = RoomOwnerGraceModel(creatorId, ownerGraceDeadline),
            status = RoomStatus.WAITING,
            currentSessionId = null,
            emptySince = now,
            timeControl = timeControl,
            gameSettings = gameSettings,
            createdAt = now,
        ),
    )

    fun findById(id: UUID): RoomModel? = roomExposedRepository.findById(id)

    /**
     * Блокирует строку комнаты до конца транзакции (`FOR UPDATE`). Все изменения комнаты, её участников и партий идут
     * под этой блокировкой, поэтому гонки (последнее место, передача владения, параллельные ходы) корректны.
     */
    fun lockById(id: UUID): RoomModel? = roomExposedRepository.lockById(id)

    fun update(room: RoomModel) = roomExposedRepository.update(room)

    /** Комнаты игр с данным статусом публикации (не больше [limit]). Без блокировок. */
    fun findIdsByGamePublishStatus(status: GamePublishStatus, limit: Int): List<UUID> =
        roomExposedRepository.findIdsByGamePublishStatus(status, limit)

    /** Удаляет комнату. Участники, подключения и партии удаляются каскадно. */
    fun delete(id: UUID) = roomExposedRepository.delete(id)

    /** Комнаты, пустующие с момента раньше [threshold] (не больше [limit]). Без блокировок. */
    fun findIdsEmptySince(threshold: Instant, limit: Int): List<UUID> = roomExposedRepository.findIdsEmptySince(threshold, limit)

    /**
     * Комнаты, у которых окно владения истекло к [now] (не больше [limit]), самые просроченные первыми. Комнаты
     * неопубликованных игр (NOT_PUBLISHED) пропускаются: их удалит NotPublishedGameCleanupJob. Без блокировок.
     */
    fun findIdsWithExpiredOwnerGrace(now: Instant, limit: Int): List<UUID> =
        roomExposedRepository.findIdsWithExpiredOwnerGrace(now, limit)
}
