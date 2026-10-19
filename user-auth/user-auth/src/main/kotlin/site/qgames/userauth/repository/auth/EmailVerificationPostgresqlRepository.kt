package site.qgames.userauth.repository.auth

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.auth.EmailVerificationEntity
import java.time.Instant
import java.util.UUID

/** Таблица email_verification. Используется только из [EmailVerificationRepository]. */
interface EmailVerificationPostgresqlRepository : CrudRepository<EmailVerificationEntity, UUID> {

    @Query(
        """
        SELECT * FROM email_verification
        WHERE user_id = :userId AND consumed_at IS NULL
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    fun findActive(userId: UUID): EmailVerificationEntity?

    /**
     * Атомарно засчитывает попытку ввода кода. Возвращает 0, если лимит исчерпан —
     * так лимит соблюдается даже при параллельных запросах в разные инстансы.
     */
    @Modifying
    @Query("UPDATE email_verification SET attempts = attempts + 1 WHERE id = :id AND attempts < :maxAttempts")
    fun registerAttempt(id: UUID, maxAttempts: Int): Int

    @Modifying
    @Query("UPDATE email_verification SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
    fun consume(id: UUID, now: Instant): Int

    @Modifying
    @Query("UPDATE email_verification SET consumed_at = :now WHERE user_id = :userId AND consumed_at IS NULL")
    fun consumeAllActive(userId: UUID, now: Instant): Int
}
