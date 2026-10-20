package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Подключения к потокам событий комнат (room_connection). Пока у пользователя есть хотя бы одно подключение к комнате,
 * он в ней присутствует. Подключения живут по аренде: инстанс, держащий поток, продлевает её, а подключения упавших
 * инстансов истекают.
 */
@Repository
class RoomConnectionRepository(
    private val roomConnectionPostgresqlRepository: RoomConnectionPostgresqlRepository,
) {

    /** Новое подключение; возвращает его id. */
    fun insert(roomId: UUID, userId: UUID, instanceId: UUID, expiresAt: Instant): UUID =
        roomConnectionPostgresqlRepository.insert(roomId, userId, instanceId, expiresAt)

    /** false — подключения уже нет (удалено по аренде или вместе с комнатой). */
    fun delete(id: UUID): Boolean = roomConnectionPostgresqlRepository.delete(id)

    fun existsForUser(roomId: UUID, userId: UUID): Boolean = roomConnectionPostgresqlRepository.existsForUser(roomId, userId)

    /**
     * Продлевает аренду подключений [ids] до [expiresAt]. Возвращает id продлённых: подключений, которых в списке
     * нет, уже не существует (их удалили по истёкшей аренде или вместе с комнатой).
     */
    fun renew(ids: Collection<UUID>, expiresAt: Instant): Set<UUID> =
        ids.chunked(RENEW_CHUNK).flatMapTo(HashSet()) { roomConnectionPostgresqlRepository.renew(it, expiresAt) }

    /** Комнаты, где у кого-то истекла аренда подключения к [now], самые просроченные первыми. Без блокировок. */
    fun findRoomIdsWithExpired(now: Instant, limit: Int): List<UUID> =
        roomConnectionPostgresqlRepository.findRoomIdsWithExpired(now, limit)

    /** Удаляет подключения комнаты с истёкшей к [now] арендой; возвращает их пользователей (с повторами). */
    fun deleteExpired(roomId: UUID, now: Instant): List<UUID> = roomConnectionPostgresqlRepository.deleteExpired(roomId, now)

    companion object {
        private const val RENEW_CHUNK = 1000
    }
}
