package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.room.RoomEntityMapper
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomTimeControlModel
import java.time.Instant
import java.util.UUID

@Repository
class RoomRepository(
    private val roomPostgresqlRepository: RoomPostgresqlRepository,
    private val roomEntityMapper: RoomEntityMapper,
) {

    /** Новая комната ждёт игроков ([RoomStatus.WAITING]). [gameSettings] — JSON настроек игры. */
    fun insert(
        gameType: RoomGameType,
        ownerId: UUID,
        timeControl: RoomTimeControlModel,
        gameSettings: String,
        now: Instant,
    ): RoomModel = roomEntityMapper.toModel(
        roomPostgresqlRepository.insert(
            gameType = gameType.name,
            ownerId = ownerId,
            status = RoomStatus.WAITING.name,
            timeControlMode = timeControl.mode.name,
            moveSeconds = timeControl.moveSeconds,
            gameSettings = gameSettings,
            now = now,
        ),
    )

    fun findById(id: UUID): RoomModel? = roomPostgresqlRepository.findById(id)?.let(roomEntityMapper::toModel)

    /**
     * Блокирует строку комнаты до конца транзакции. Все изменения комнаты, её участников и партий идут под этой
     * блокировкой, поэтому гонки (последнее место, передача владения, параллельные ходы) корректны на любом числе инстансов.
     */
    fun lockById(id: UUID): RoomModel? = roomPostgresqlRepository.lockById(id)?.let(roomEntityMapper::toModel)

    /** Как [lockById], но уже заблокированную комнату пропускает (SKIP LOCKED) и возвращает null — для джобов. */
    fun lockByIdSkipLocked(id: UUID): RoomModel? =
        roomPostgresqlRepository.lockByIdSkipLocked(id)?.let(roomEntityMapper::toModel)

    /**
     * Блокирует комнату партии [sessionId], если партия не на паузе и время хода в ней истекло к [now]. Уже заблокированную комнату
     * пропускает (SKIP LOCKED) и возвращает null — её обработает следующий запуск джоба.
     */
    fun lockWithExpiredMove(sessionId: UUID, now: Instant): RoomModel? =
        roomPostgresqlRepository.lockWithExpiredMove(sessionId, now)?.let(roomEntityMapper::toModel)

    fun update(room: RoomModel) = roomPostgresqlRepository.update(roomEntityMapper.toEntity(room))

    fun delete(id: UUID) = roomPostgresqlRepository.delete(id)

    /** Удаляет комнаты, пустующие с момента раньше [threshold]. Участники и партии удаляются каскадно. */
    fun deleteEmptySince(threshold: Instant): Int = roomPostgresqlRepository.deleteEmptySince(threshold)
}
