package site.qgames.usergateway.mapper.user

import site.qgames.usergateway.publicapi.dto.user.Avatar
import site.qgames.usergateway.publicapi.dto.user.ChangePasswordRequest
import site.qgames.usergateway.publicapi.dto.user.LoginMethod
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.publicapi.dto.user.UpdateProfileRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse
import site.qgames.userauth.gatewayapi.dto.user.Avatar as UserAuthAvatar
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest as UserAuthChangePasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.LoginMethod as UserAuthLoginMethod
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest as UserAuthSetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UpdateProfileRequest as UserAuthUpdateProfileRequest
import site.qgames.userauth.gatewayapi.dto.user.UserResponse as UserAuthUserResponse

/**
 * Маппинг между публичным контрактом gateway и контрактом user-auth. Внутренние DTO наружу не отдаются.
 */
fun UserAuthUserResponse.toPublic() = UserResponse(
    id = id,
    email = email,
    emailVerified = emailVerified,
    nickname = nickname,
    avatar = avatar.toPublic(),
    loginMethods = loginMethods.map { it.toPublic() },
)

fun UserAuthLoginMethod.toPublic(): LoginMethod = when (this) {
    UserAuthLoginMethod.PASSWORD -> LoginMethod.PASSWORD
    UserAuthLoginMethod.GOOGLE -> LoginMethod.GOOGLE
}

fun UserAuthAvatar.toPublic(): Avatar = when (this) {
    UserAuthAvatar.CAT -> Avatar.CAT
    UserAuthAvatar.DOG -> Avatar.DOG
}

fun Avatar.toUserAuth(): UserAuthAvatar = when (this) {
    Avatar.CAT -> UserAuthAvatar.CAT
    Avatar.DOG -> UserAuthAvatar.DOG
}

fun SetPasswordRequest.toUserAuth() = UserAuthSetPasswordRequest(password)

fun ChangePasswordRequest.toUserAuth() = UserAuthChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword)

fun UpdateProfileRequest.toUserAuth() = UserAuthUpdateProfileRequest(nickname = nickname, avatar = avatar?.toUserAuth())
