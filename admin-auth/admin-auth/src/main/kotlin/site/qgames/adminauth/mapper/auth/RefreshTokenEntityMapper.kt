package site.qgames.adminauth.mapper.auth

import org.springframework.stereotype.Component
import site.qgames.adminauth.entity.auth.RefreshTokenEntity
import site.qgames.adminauth.model.auth.RefreshTokenModel

@Component
class RefreshTokenEntityMapper {

    fun toModel(entity: RefreshTokenEntity) = RefreshTokenModel(
        id = entity.id!!,
        adminId = entity.adminId,
        familyId = entity.familyId,
        tokenHash = entity.tokenHash,
        expiresAt = entity.expiresAt,
        revokedAt = entity.revokedAt,
        rotatedAt = entity.rotatedAt,
        createdAt = entity.createdAt,
    )
}
