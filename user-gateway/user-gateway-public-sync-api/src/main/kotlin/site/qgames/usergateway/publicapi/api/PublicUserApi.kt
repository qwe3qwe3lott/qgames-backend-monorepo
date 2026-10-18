package site.qgames.usergateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse

/**
 * Текущий пользователь. Требует аутентификации (access-токен в куке или заголовке Authorization).
 */
@Tag(
    name = "Текущий пользователь",
    description = "Профиль и способы входа. Требуется аутентификация: кука access_token " +
        "(или заголовок Authorization: Bearer).",
)
@HttpExchange("/api/users/me")
interface PublicUserApi {

    @Operation(
        summary = "Данные текущего пользователя",
        description = "Email, статус его подтверждения и привязанные способы входа. Подходит, чтобы проверить, " +
            "залогинен ли пользователь.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Данные пользователя"),
        ApiResponse(
            responseCode = "401",
            description = "Нет access-токена, он истёк или недействителен (UNAUTHENTICATED) — нужно вызвать /api/auth/refresh",
        ),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange
    fun getCurrentUser(): UserResponse

    @Operation(
        summary = "Добавить вход по паролю",
        description = "Для аккаунта без пароля (например, созданного через Google). Логином становится email аккаунта.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пароль добавлен, возвращены обновлённые данные пользователя"),
        ApiResponse(responseCode = "400", description = "Пароль не проходит требования (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "409", description = "Пароль уже задан (LOGIN_METHOD_ALREADY_LINKED)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/login-methods/password")
    fun addPasswordLogin(@Valid @RequestBody request: SetPasswordRequest): UserResponse

    @Operation(
        summary = "Привязать Google-аккаунт",
        description = "Явная привязка Google к текущему аккаунту. Email Google-аккаунта может отличаться от email аккаунта.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Google привязан, возвращены обновлённые данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Нет действительного access-токена (UNAUTHENTICATED) или ID token недействителен (INVALID_GOOGLE_TOKEN)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "У пользователя уже есть Google-аккаунт (LOGIN_METHOD_ALREADY_LINKED) или этот Google-аккаунт " +
                "привязан к другому пользователю (GOOGLE_ACCOUNT_ALREADY_LINKED)",
        ),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/login-methods/google")
    fun addGoogleLogin(@Valid @RequestBody request: GoogleIdTokenRequest): UserResponse
}
