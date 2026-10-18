package site.qgames.usergateway.mapper.user

import site.qgames.usergateway.publicapi.dto.user.LoginMethod
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse
import site.qgames.userauth.gatewayapi.dto.user.LoginMethod as UserAuthLoginMethod
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest as UserAuthSetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UserResponse as UserAuthUserResponse

/**
 * Маппинг между публичным контрактом gateway и контрактом user-auth. Внутренние DTO наружу не отдаются.
 */
fun UserAuthUserResponse.toPublic() = UserResponse(
    id = id,
    email = email,
    emailVerified = emailVerified,
    loginMethods = loginMethods.map { it.toPublic() },
)

fun UserAuthLoginMethod.toPublic(): LoginMethod = when (this) {
    UserAuthLoginMethod.PASSWORD -> LoginMethod.PASSWORD
    UserAuthLoginMethod.GOOGLE -> LoginMethod.GOOGLE
}

fun SetPasswordRequest.toUserAuth() = UserAuthSetPasswordRequest(password)
