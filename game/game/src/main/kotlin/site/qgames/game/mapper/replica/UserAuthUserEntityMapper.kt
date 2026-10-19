package site.qgames.game.mapper.replica

import org.springframework.stereotype.Component
import site.qgames.game.entity.replica.UserAuthUserEntity
import site.qgames.game.model.replica.UserAuthUserModel
import site.qgames.game.model.replica.UserAvatar

@Component
class UserAuthUserEntityMapper {

    fun toModel(entity: UserAuthUserEntity) = UserAuthUserModel(
        id = entity.id,
        nickname = entity.nickname,
        avatar = UserAvatar.valueOf(entity.avatar),
        version = entity.version,
    )

    fun toEntity(model: UserAuthUserModel) = UserAuthUserEntity(
        id = model.id,
        nickname = model.nickname,
        avatar = model.avatar.name,
        version = model.version,
    )
}
