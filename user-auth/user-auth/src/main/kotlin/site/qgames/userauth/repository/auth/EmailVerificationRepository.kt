package site.qgames.userauth.repository.auth

import org.springframework.stereotype.Repository
import site.qgames.userauth.entity.auth.EmailVerificationEntity
import site.qgames.userauth.mapper.auth.AuthEntityMapper
import site.qgames.userauth.model.auth.EmailVerificationModel
import java.time.Instant
import java.util.UUID

@Repository
class EmailVerificationRepository(
    private val emailVerificationPostgresqlRepository: EmailVerificationPostgresqlRepository,
    private val authEntityMapper: AuthEntityMapper,
) {

    fun insert(userId: UUID, codeHash: String, expiresAt: Instant, now: Instant): EmailVerificationModel =
        authEntityMapper.toModel(
            emailVerificationPostgresqlRepository.save(
                EmailVerificationEntity(userId = userId, codeHash = codeHash, expiresAt = expiresAt, createdAt = now),
            ),
        )

    /** Последний неиспользованный код пользователя. */
    fun findActive(userId: UUID): EmailVerificationModel? =
        emailVerificationPostgresqlRepository.findActive(userId)?.let(authEntityMapper::toModel)

    /**
     * Атомарно засчитывает попытку ввода кода. Возвращает 0, если лимит исчерпан —
     * так лимит соблюдается даже при параллельных запросах в разные инстансы.
     */
    fun registerAttempt(id: UUID, maxAttempts: Int): Int = emailVerificationPostgresqlRepository.registerAttempt(id, maxAttempts)

    fun consume(id: UUID, now: Instant): Int = emailVerificationPostgresqlRepository.consume(id, now)

    fun consumeAllActive(userId: UUID, now: Instant): Int = emailVerificationPostgresqlRepository.consumeAllActive(userId, now)
}
