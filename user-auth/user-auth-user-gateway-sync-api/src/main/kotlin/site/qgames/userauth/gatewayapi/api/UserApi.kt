package site.qgames.userauth.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PatchExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UpdateProfileRequest
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
        summary = "Изменить профиль",
        description = "Меняет ник и/или аватар: изменяется только переданное. Ник не уникален. Новый профиль публикуется " +
            "событием UserProfileChanged, и другие сервисы (например, game) получают его через CDC.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Профиль изменён, возвращены обновлённые данные пользователя"),
        ApiResponse(
            responseCode = "400",
            description = "Ничего не передано или ник не проходит требования: 3–32 символа без пробелов по краям (VALIDATION_ERROR)",
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
    )
    @PatchExchange("/profile")
    fun updateProfile(@Valid @RequestBody request: UpdateProfileRequest): UserResponse

    @Operation(
        summary = "Добавить вход по паролю",
        description = "Для аккаунта, у которого ещё нет пароля (например, созданного через Google).",
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
        summary = "Сменить пароль",
        description = "Проверяет текущий пароль и задаёт новый. Все сессии пользователя завершаются, в ответе — " +
            "новая пара токенов для клиента, сменившего пароль. Профиль пользователя не меняется, событие не публикуется.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Пароль изменён, возвращены данные пользователя и новая пара токенов",
        ),
        ApiResponse(
            responseCode = "400",
            description = "Новый пароль не проходит требования (VALIDATION_ERROR) или текущий пароль неверен " +
                "(INVALID_CURRENT_PASSWORD)",
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
        ApiResponse(responseCode = "409", description = "У аккаунта нет пароля (PASSWORD_NOT_SET)"),
    )
    @PutExchange("/login-methods/password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): AuthResponse

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
