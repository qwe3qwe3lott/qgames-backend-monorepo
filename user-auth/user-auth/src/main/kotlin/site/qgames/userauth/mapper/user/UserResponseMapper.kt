package site.qgames.userauth.mapper.user

import org.springframework.stereotype.Component
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserDetailsModel

/** Модели пользователя ↔ DTO контракта. */
@Component
class UserResponseMapper {

    fun toResponse(details: UserDetailsModel): UserResponse {
        val user = details.user
        return UserResponse(
            id = user.id,
            login = user.login,
            nickname = user.nickname,
            avatar = Avatar.valueOf(user.avatar.name),
            loginMethods = details.loginMethods.map { LoginMethod.valueOf(it.name) },
        )
    }

    fun toModel(avatar: Avatar): UserAvatar = UserAvatar.valueOf(avatar.name)
}
