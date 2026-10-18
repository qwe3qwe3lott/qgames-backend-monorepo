package site.qgames.usergateway.mapper.auth

import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.auth.LoginRequest
import site.qgames.usergateway.publicapi.dto.auth.RegisterRequest
import site.qgames.usergateway.publicapi.dto.auth.RegistrationResponse
import site.qgames.usergateway.publicapi.dto.auth.ResendCodeRequest
import site.qgames.usergateway.publicapi.dto.auth.VerifyEmailRequest
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest as UserAuthGoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest as UserAuthLoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegisterRequest as UserAuthRegisterRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegistrationResponse as UserAuthRegistrationResponse
import site.qgames.userauth.gatewayapi.dto.auth.ResendCodeRequest as UserAuthResendCodeRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest as UserAuthVerifyEmailRequest

/**
 * Маппинг между публичным контрактом gateway и контрактом user-auth. Внутренние DTO наружу не отдаются.
 */
fun RegisterRequest.toUserAuth() = UserAuthRegisterRequest(email, password)

fun VerifyEmailRequest.toUserAuth() = UserAuthVerifyEmailRequest(email, code)

fun ResendCodeRequest.toUserAuth() = UserAuthResendCodeRequest(email)

fun LoginRequest.toUserAuth() = UserAuthLoginRequest(email, password)

fun GoogleIdTokenRequest.toUserAuth() = UserAuthGoogleIdTokenRequest(idToken)

fun UserAuthRegistrationResponse.toPublic() = RegistrationResponse(userId, email)
