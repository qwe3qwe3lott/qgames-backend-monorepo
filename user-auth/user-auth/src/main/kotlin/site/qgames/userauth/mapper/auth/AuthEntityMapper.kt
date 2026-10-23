package site.qgames.userauth.mapper.auth

import org.springframework.stereotype.Component
import site.qgames.userauth.entity.auth.RefreshTokenEntity
import site.qgames.userauth.model.auth.RefreshTokenModel

@Component
class AuthEntityMapper {

    fun toModel(entity: RefreshTokenEntity) = RefreshTokenModel(
        id = entity.id!!,
        userId = entity.userId,
        familyId = entity.familyId,
        tokenHash = entity.tokenHash,
        expiresAt = entity.expiresAt,
        revokedAt = entity.revokedAt,
        rotatedAt = entity.rotatedAt,
        createdAt = entity.createdAt,
    )
}
