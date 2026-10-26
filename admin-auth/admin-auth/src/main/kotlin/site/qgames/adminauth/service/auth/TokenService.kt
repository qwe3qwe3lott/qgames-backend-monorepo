package site.qgames.adminauth.service.auth

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.adminauth.config.CryptoConfig
import com.nimbusds.jose.jwk.ECKey
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.adminauth.config.AuthProperties
import site.qgames.adminauth.exception.auth.AuthErrors
import site.qgames.adminauth.model.auth.TokenPairModel
import site.qgames.adminauth.repository.auth.RefreshTokenRepository
import site.qgames.adminauth.util.Hashing
import site.qgames.common.exception.exception.QgamesException
import java.time.Clock
import java.util.UUID

/**
 * Access-токен — короткоживущий JWT (ES256, свой ключ и issuer admin-auth), проверяется Security-цепочкой админки по публичному ключу.
 * Refresh-токен — случайная строка, в БД лежит только её SHA-256. При каждом обновлении токен ротируется и получает
 * полный срок (скользящая сессия), а повторное предъявление уже использованного токена (после окна терпимости)
 * отзывает всю цепочку (family).
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
    fun issue(adminId: UUID, familyId: UUID = UUID.randomUUID()): TokenPairModel {
        val now = clock.instant()
        val accessTokenExpiresAt = now.plus(properties.jwt.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.jwt.issuer)
            .subject(adminId.toString())
            .issuedAt(now)
            .expiresAt(accessTokenExpiresAt)
            .id(UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(jwtSigningKey.keyID).build()
        val accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        val refreshToken = Hashing.randomUrlSafeToken()
        val refreshTokenExpiresAt = now.plus(properties.refreshTokenTtl)
        refreshTokenRepository.insert(adminId, familyId, Hashing.sha256Hex(refreshToken), refreshTokenExpiresAt, now)
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
            return existing.adminId to issue(existing.adminId, existing.familyId)
        }
        // Токен уже отозван — возможно, только что параллельным запросом, поэтому перечитываем.
        val revoked = refreshTokenRepository.findByTokenHash(tokenHash)!!
        val rotatedAt = revoked.rotatedAt
        val withinGrace = rotatedAt != null && !rotatedAt.plus(properties.refreshReuseGrace).isBefore(now)
        if (withinGrace && refreshTokenRepository.existsActiveInFamily(revoked.familyId)) {
            return revoked.adminId to issue(revoked.adminId, revoked.familyId)
        }
        refreshTokenRepository.revokeFamily(revoked.familyId, now)
        throw AuthErrors.refreshTokenReused()
    }

    /** Отзывает сессию токена. Неизвестный токен игнорируется, повторный выход ничего не меняет. */
    @Transactional
    fun revokeSession(refreshToken: String) {
        val existing = refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(refreshToken)) ?: return
        refreshTokenRepository.revokeFamily(existing.familyId, clock.instant())
    }
}
