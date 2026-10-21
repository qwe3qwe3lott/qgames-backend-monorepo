package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.session.RoomSessionEntityMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import java.time.Instant
import java.util.UUID

@Repository
class RoomSessionRepository(
    private val roomSessionPostgresqlRepository: RoomSessionPostgresqlRepository,
    private val roomSessionEntityMapper: RoomSessionEntityMapper,
) {

    /**
     * Новая партия ([RoomSessionStatus.IN_PROGRESS]) с пустым журналом; места игроков — по порядку в [players].
     * [timeControl] и [gameSettings] (JSON) — снимок настроек комнаты на момент старта.
     */
    fun insert(
        roomId: UUID,
        gameCode: GameCode,
        stateSchemaVersion: Int,
        seed: Long,
        timeControl: RoomTimeControlModel,
        gameSettings: String,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionModel = roomSessionEntityMapper.toModel(
        roomSessionPostgresqlRepository.insert(
            roomId = roomId,
            gameCode = gameCode.code,
            status = RoomSessionStatus.IN_PROGRESS.name,
            stateSchemaVersion = stateSchemaVersion,
            seed = seed,
            timeControlMode = timeControl.mode.name,
            moveSeconds = timeControl.moveSeconds,
            gameSettings = gameSettings,
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
     * Идущие партии с идущим таймером, у которых время хода истекло к [now], — самые просроченные первыми. Партии
     * принудительно отключённых игр (FORCE_DISABLED) пропускаются. Без блокировок.
     */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> =
        roomSessionPostgresqlRepository.findIdsWithExpiredMove(now, limit)

    /** После записи событий журнала: последний seq [lastSeq], статус и проекция таймера [moveDeadline]. */
    fun advance(id: UUID, lastSeq: Long, status: RoomSessionStatus, moveDeadline: Instant?, finishedAt: Instant?) =
        roomSessionPostgresqlRepository.advance(id, lastSeq, status.name, moveDeadline, finishedAt)
}
