package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.room.RoomEntityMapper
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomStatus
import java.time.Instant
import java.util.UUID

@Repository
class RoomRepository(
    private val roomPostgresqlRepository: RoomPostgresqlRepository,
    private val roomEntityMapper: RoomEntityMapper,
) {

    /** Новая комната ждёт игроков ([RoomStatus.WAITING]). */
    fun insert(gameType: RoomGameType, ownerId: UUID, now: Instant): RoomModel =
        roomEntityMapper.toModel(roomPostgresqlRepository.insert(gameType.name, ownerId, RoomStatus.WAITING.name, now))

    fun findById(id: UUID): RoomModel? = roomPostgresqlRepository.findById(id)?.let(roomEntityMapper::toModel)

    /**
     * Блокирует строку комнаты до конца транзакции. Все изменения комнаты, её участников и партий идут под этой
     * блокировкой, поэтому гонки (последнее место, передача владения, параллельные ходы) корректны на любом числе инстансов.
     */
    fun lockById(id: UUID): RoomModel? = roomPostgresqlRepository.lockById(id)?.let(roomEntityMapper::toModel)

    fun update(room: RoomModel) = roomPostgresqlRepository.update(roomEntityMapper.toEntity(room))

    fun delete(id: UUID) = roomPostgresqlRepository.delete(id)

    /** Удаляет комнаты, пустующие с момента раньше [threshold]. Участники и партии удаляются каскадно. */
    fun deleteEmptySince(threshold: Instant): Int = roomPostgresqlRepository.deleteEmptySince(threshold)
}
