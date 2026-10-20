package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.session.RoomSessionEntityMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class RoomSessionRepository(
    private val roomSessionPostgresqlRepository: RoomSessionPostgresqlRepository,
    private val roomSessionEntityMapper: RoomSessionEntityMapper,
) {

    /**
     * Новая партия ([RoomSessionStatus.IN_PROGRESS]); места игроков — по порядку в [players].
     * [timeControl] и [gameSettings] (JSON) — снимок настроек комнаты на момент старта.
     */
    fun insert(
        roomId: UUID,
        gameCode: GameCode,
        state: String,
        stateSchemaVersion: Int,
        seed: Long,
        timeControl: RoomTimeControlModel,
        gameSettings: String,
        moveDeadline: Instant?,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionModel = roomSessionEntityMapper.toModel(
        roomSessionPostgresqlRepository.insert(
            roomId = roomId,
            gameCode = gameCode.code,
            status = RoomSessionStatus.IN_PROGRESS.name,
            state = state,
            stateSchemaVersion = stateSchemaVersion,
            seed = seed,
            timeControlMode = timeControl.mode.name,
            moveSeconds = timeControl.moveSeconds,
            gameSettings = gameSettings,
            moveDeadline = moveDeadline,
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
     * Идущие партии не на паузе, у которых время хода истекло к [now], — самые просроченные первыми. Партии
     * принудительно отключённых игр (FORCE_DISABLED) пропускаются. Без блокировок.
     */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> =
        roomSessionPostgresqlRepository.findIdsWithExpiredMove(now, limit)

    /**
     * Оптимистичная блокировка: обновляет состояние и дедлайн хода, только если версия не изменилась.
     * Возвращает false, если партию уже изменил другой запрос.
     */
    fun updateState(
        id: UUID,
        expectedVersion: Long,
        state: String,
        status: RoomSessionStatus,
        moveDeadline: Instant?,
        finishedAt: Instant?,
    ): Boolean = roomSessionPostgresqlRepository.updateState(id, expectedVersion, state, status.name, moveDeadline, finishedAt)

    fun clearMoveDeadline(id: UUID) = roomSessionPostgresqlRepository.clearMoveDeadline(id)

    /** Ставит партию на паузу с момента [now]; уже стоящую на паузе не меняет. */
    fun pause(id: UUID, now: Instant) = roomSessionPostgresqlRepository.pause(id, now)

    /** Снимает паузу; дедлайн хода (если он есть) сдвигается на длительность паузы [shift]. */
    fun resume(id: UUID, shift: Duration) = roomSessionPostgresqlRepository.resume(id, shift.toNanos() / 1_000)

    /** Завершает партию, снимает таймер хода и паузу. */
    fun finish(id: UUID, status: RoomSessionStatus, finishedAt: Instant) =
        roomSessionPostgresqlRepository.finish(id, status.name, finishedAt)
}
