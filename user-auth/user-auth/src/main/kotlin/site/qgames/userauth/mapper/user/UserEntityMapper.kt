package site.qgames.userauth.mapper.user

import org.springframework.stereotype.Component
import site.qgames.userauth.entity.user.UserEntity
import site.qgames.userauth.entity.user.UserIdentityEntity
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserIdentityModel
import site.qgames.userauth.model.user.UserModel

@Component
class UserEntityMapper {

    fun toModel(entity: UserEntity) = UserModel(
        id = entity.id!!,
        email = entity.email,
        emailVerified = entity.emailVerified,
        nickname = entity.nickname,
        avatar = UserAvatar.valueOf(entity.avatar),
        createdAt = entity.createdAt,
        version = entity.version!!,
    )

    fun toEntity(model: UserModel) = UserEntity(
        id = model.id,
        email = model.email,
        emailVerified = model.emailVerified,
        nickname = model.nickname,
        avatar = model.avatar.name,
        createdAt = model.createdAt,
        version = model.version,
    )

    fun toModel(entity: UserIdentityEntity) = UserIdentityModel(
        id = entity.id!!,
        userId = entity.userId,
        provider = IdentityProvider.valueOf(entity.provider),
        subject = entity.subject,
        passwordHash = entity.passwordHash,
        createdAt = entity.createdAt,
    )
}
