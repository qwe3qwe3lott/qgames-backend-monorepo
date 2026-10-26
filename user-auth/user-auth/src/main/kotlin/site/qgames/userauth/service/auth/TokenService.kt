package site.qgames.userauth.service.auth

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.userauth.config.CryptoConfig
import com.nimbusds.jose.jwk.ECKey
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.common.exception.exception.QgamesException
import site.qgames.userauth.config.AuthProperties
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.auth.TokenPairModel
import site.qgames.userauth.repository.auth.RefreshTokenRepository
import site.qgames.userauth.util.Hashing
import java.time.Clock
import java.util.UUID

/**
 * Access-токен — короткоживущий JWT (ES256), проверяется Security-цепочкой портала по публичному ключу.
 * Refresh-токен — случайная строка, в БД лежит только её SHA-256. При каждом обновлении токен ротируется,
 * а повторное предъявление уже использованного токена (после окна терпимости) отзывает всю цепочку (family).
 */
@Service
class TokenService(
    @Qualifier(CryptoConfig.JWT_ENCODER) private val jwtEncoder: JwtEncoder,
    @Qualifier(CryptoConfig.JWT_SIGNING_KEY) private val jwtSigningKey: ECKey,
    private val properties: AuthProperties,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {

    @Transactional
    fun issue(userId: UUID, familyId: UUID = UUID.randomUUID()): TokenPairModel {
        val now = clock.instant()
        val accessTokenExpiresAt = now.plus(properties.jwt.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.jwt.issuer)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(accessTokenExpiresAt)
            .id(UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(jwtSigningKey.keyID).build()
        val accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        val refreshToken = Hashing.randomUrlSafeToken()
        val refreshTokenExpiresAt = now.plus(properties.refreshTokenTtl)
        refreshTokenRepository.insert(userId, familyId, Hashing.sha256Hex(refreshToken), refreshTokenExpiresAt, now)
        return TokenPairModel(accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt)
    }

    /**
     * Возвращает владельца токена и новую пару. noRollbackFor нужен, чтобы отзыв цепочки при reuse
     * сохранился, несмотря на выброшенную ошибку.
     *
     * Повтор уже ротированного токена в течение [AuthProperties.refreshReuseGrace] не считается кражей:
     * так ведут себя параллельные вкладки, обновляющие сессию одной кукой. Им выдаётся ещё одна пара той же
     * цепочки. Повтор позже окна, после выхода или после обнаружения кражи отзывает всю цепочку.
     */
    @Transactional(noRollbackFor = [QgamesException::class])
    fun rotate(refreshToken: String): Pair<UUID, TokenPairModel> {
        val now = clock.instant()
        val tokenHash = Hashing.sha256Hex(refreshToken)
        val existing = refreshTokenRepository.findByTokenHash(tokenHash) ?: throw AuthErrors.invalidRefreshToken()
        if (!existing.expiresAt.isAfter(now)) {
            throw AuthErrors.invalidRefreshToken()
        }
        if (existing.revokedAt == null && refreshTokenRepository.rotate(existing.id, now) == 1) {
            return existing.userId to issue(existing.userId, existing.familyId)
        }
        // Токен уже отозван — возможно, только что параллельным запросом, поэтому перечитываем.
        val revoked = refreshTokenRepository.findByTokenHash(tokenHash)!!
        val rotatedAt = revoked.rotatedAt
        val withinGrace = rotatedAt != null && !rotatedAt.plus(properties.refreshReuseGrace).isBefore(now)
        if (withinGrace && refreshTokenRepository.existsActiveInFamily(revoked.familyId)) {
            return revoked.userId to issue(revoked.userId, revoked.familyId)
        }
        refreshTokenRepository.revokeFamily(revoked.familyId, now)
        throw AuthErrors.refreshTokenReused()
    }

    @Transactional
    fun revokeSession(refreshToken: String) {
        val existing = refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(refreshToken)) ?: return
        refreshTokenRepository.revokeFamily(existing.familyId, clock.instant())
    }

    /** Отзывает все сессии пользователя. Уже выданные access-токены действуют до истечения срока. */
    @Transactional
    fun revokeAllSessions(userId: UUID) {
        refreshTokenRepository.revokeAllByUserId(userId, clock.instant())
    }
}
