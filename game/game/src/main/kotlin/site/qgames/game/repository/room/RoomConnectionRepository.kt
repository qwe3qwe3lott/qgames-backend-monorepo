package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Подключения к потокам событий комнат (room_connection). Пока у пользователя есть хотя бы одно подключение к комнате,
 * он в ней присутствует. Подключения живут по аренде: приложение продлевает аренду живых потоков, а подключения,
 * которые никто не продлевает (процесс упал или перезапущен), истекают.
 */
@Repository
class RoomConnectionRepository(
    private val roomConnectionExposedRepository: RoomConnectionExposedRepository,
) {

    /** Новое подключение; возвращает его id. */
    fun insert(roomId: UUID, userId: UUID, expiresAt: Instant): UUID = roomConnectionExposedRepository.insert(roomId, userId, expiresAt)

    /** false — подключения уже нет (удалено по аренде или вместе с комнатой). */
    fun delete(id: UUID): Boolean = roomConnectionExposedRepository.delete(id)

    /** Удаляет все подключения пользователя к комнате (исключение из комнаты). */
    fun deleteForUser(roomId: UUID, userId: UUID) = roomConnectionExposedRepository.deleteForUser(roomId, userId)

    fun existsForUser(roomId: UUID, userId: UUID): Boolean = roomConnectionExposedRepository.existsForUser(roomId, userId)

    /**
     * Продлевает аренду подключений [ids] до [expiresAt]. Возвращает id продлённых: подключений, которых в списке
     * нет, уже не существует (их удалили по истёкшей аренде или вместе с комнатой).
     */
    fun renew(ids: Collection<UUID>, expiresAt: Instant): Set<UUID> =
        ids.chunked(RENEW_CHUNK).flatMapTo(HashSet()) { roomConnectionExposedRepository.renew(it, expiresAt) }

    /** Комнаты, где у кого-то истекла аренда подключения к [now], самые просроченные первыми. Без блокировок. */
    fun findRoomIdsWithExpired(now: Instant, limit: Int): List<UUID> = roomConnectionExposedRepository.findRoomIdsWithExpired(now, limit)

    /** Удаляет подключения комнаты с истёкшей к [now] арендой; возвращает их пользователей (с повторами). */
    fun deleteExpired(roomId: UUID, now: Instant): List<UUID> = roomConnectionExposedRepository.deleteExpired(roomId, now)

    companion object {
        /** Не больше параметров в одном запросе. */
        private const val RENEW_CHUNK = 1000
    }
}
