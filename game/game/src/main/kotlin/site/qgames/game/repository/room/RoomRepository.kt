package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomOptionsModel
import site.qgames.game.util.newId
import java.time.Instant
import java.util.UUID

@Repository
class RoomRepository(
    private val roomExposedRepository: RoomExposedRepository,
) {

    /**
     * Новая комната ждёт игроков ([RoomStatus.WAITING]). [simpleSettings] и [advancedSettings] — JSON настроек игры
     * (simple null — advanced-режим). Участников и владельца у неё нет, она пустая с [now]: первый вошедший станет
     * владельцем.
     */
    fun insert(
        gameCode: GameCode,
        options: RoomOptionsModel,
        simpleSettings: String?,
        advancedSettings: String,
        now: Instant,
    ): RoomModel = roomExposedRepository.insert(
        RoomModel(
            id = newId(),
            gameCode = gameCode,
            ownerId = null,
            status = RoomStatus.WAITING,
            currentSessionId = null,
            emptySince = now,
            options = options,
            simpleSettings = simpleSettings,
            advancedSettings = advancedSettings,
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

    /** Сдвигает seq событий потока комнаты и возвращает новый; вызывается под блокировкой комнаты. */
    fun nextEventSeq(id: UUID): Long = roomExposedRepository.nextEventSeq(id)

    /** seq последнего события потока комнаты; null — комнаты нет. */
    fun findEventSeq(id: UUID): Long? = roomExposedRepository.findEventSeq(id)

    /** Комнаты игр с данным статусом публикации (не больше [limit]). Без блокировок. */
    fun findIdsByGamePublishStatus(status: GamePublishStatus, limit: Int): List<UUID> =
        roomExposedRepository.findIdsByGamePublishStatus(status, limit)

    /** Удаляет комнату. Участники, подключения и партии удаляются каскадно. */
    fun delete(id: UUID) = roomExposedRepository.delete(id)

    /** Комнаты, пустующие с момента раньше [threshold] (не больше [limit]). Без блокировок. */
    fun findIdsEmptySince(threshold: Instant, limit: Int): List<UUID> = roomExposedRepository.findIdsEmptySince(threshold, limit)
}
