package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.session.RoomSessionEntityMapper
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import java.time.Instant
import java.util.UUID

@Repository
class RoomSessionRepository(
    private val roomSessionPostgresqlRepository: RoomSessionPostgresqlRepository,
    private val roomSessionEntityMapper: RoomSessionEntityMapper,
) {

    /** Новая партия ([RoomSessionStatus.IN_PROGRESS]); места игроков — по порядку в [players]. */
    fun insert(
        roomId: UUID,
        gameType: RoomGameType,
        state: String,
        stateSchemaVersion: Int,
        seed: Long,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionModel = roomSessionEntityMapper.toModel(
        roomSessionPostgresqlRepository.insert(
            roomId = roomId,
            gameType = gameType.name,
            status = RoomSessionStatus.IN_PROGRESS.name,
            state = state,
            stateSchemaVersion = stateSchemaVersion,
            seed = seed,
            players = players,
            now = now,
        ),
    )

    fun findById(id: UUID): RoomSessionModel? =
        roomSessionPostgresqlRepository.findById(id)?.let(roomSessionEntityMapper::toModel)

    /** Последняя партия комнаты (для показа результата после завершения или отмены). */
    fun findLatest(roomId: UUID): RoomSessionModel? =
        roomSessionPostgresqlRepository.findLatest(roomId)?.let(roomSessionEntityMapper::toModel)

    /** Игроки партии в порядке мест. */
    fun findPlayers(sessionId: UUID): List<UUID> = roomSessionPostgresqlRepository.findPlayers(sessionId)

    /**
     * Оптимистичная блокировка: обновляет состояние, только если версия не изменилась.
     * Возвращает false, если партию уже изменил другой запрос.
     */
    fun updateState(id: UUID, expectedVersion: Long, state: String, status: RoomSessionStatus, finishedAt: Instant?): Boolean =
        roomSessionPostgresqlRepository.updateState(id, expectedVersion, state, status.name, finishedAt)

    fun finish(id: UUID, status: RoomSessionStatus, finishedAt: Instant) =
        roomSessionPostgresqlRepository.finish(id, status.name, finishedAt)
}
