package site.qgames.userauth.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UserResponse

/**
 * Операции над текущим пользователем. Идентификатор пользователя приходит в заголовке X-User-Id от gateway.
 */
@Tag(
    name = "Текущий пользователь (для gateway)",
    description = "Внутренние ручки для user-gateway. Пользователь определяется по заголовку X-User-Id, " +
        "который gateway ставит из проверенного JWT.",
)
@HttpExchange("/api/users/me")
interface UserApi {

    @Operation(
        summary = "Данные текущего пользователя",
        description = "Возвращает email, статус его подтверждения и привязанные способы входа.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Данные пользователя"),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
    )
    @GetExchange
    fun getCurrentUser(): UserResponse

    @Operation(
        summary = "Добавить вход по паролю",
        description = "Для аккаунта, у которого ещё нет пароля (например, созданного через Google). " +
            "Логином становится email аккаунта.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пароль добавлен, возвращены обновлённые данные пользователя"),
        ApiResponse(responseCode = "400", description = "Пароль не проходит требования (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
        ApiResponse(responseCode = "409", description = "Пароль уже задан (LOGIN_METHOD_ALREADY_LINKED)"),
    )
    @PostExchange("/login-methods/password")
    fun addPasswordLogin(@Valid @RequestBody request: SetPasswordRequest): UserResponse

    @Operation(
        summary = "Привязать Google-аккаунт",
        description = "Явная привязка из залогиненной сессии. Email Google-аккаунта может отличаться от email аккаунта.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Google привязан, возвращены обновлённые данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Нет заголовка X-User-Id (UNAUTHENTICATED) или ID token недействителен (INVALID_GOOGLE_TOKEN)",
        ),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
        ApiResponse(
            responseCode = "409",
            description = "У пользователя уже есть Google-аккаунт (LOGIN_METHOD_ALREADY_LINKED) или этот Google-аккаунт " +
                "привязан к другому пользователю (GOOGLE_ACCOUNT_ALREADY_LINKED)",
        ),
    )
    @PostExchange("/login-methods/google")
    fun addGoogleLogin(@Valid @RequestBody request: GoogleIdTokenRequest): UserResponse
}
