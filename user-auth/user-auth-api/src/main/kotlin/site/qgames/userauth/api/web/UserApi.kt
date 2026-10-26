package site.qgames.userauth.api.web

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
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.user.ChangePasswordRequest
import site.qgames.userauth.api.dto.user.SetPasswordRequest
import site.qgames.userauth.api.dto.user.UpdateProfileRequest
import site.qgames.userauth.api.dto.user.UserResponse

/**
 * Текущий пользователь. Требует аутентификации (access-токен в куке или заголовке Authorization).
 */
@Tag(
    name = "users",
    description = "Текущий пользователь. Профиль и способы входа. Требуется аутентификация: кука access_token " +
        "(или заголовок Authorization: Bearer).",
)
@HttpExchange("/api/users/me")
interface UserApi {

    @Operation(
        summary = "Данные текущего пользователя",
        description = "Логин, ник, аватар и привязанные способы входа. Подходит, чтобы проверить, " +
            "залогинен ли пользователь.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Данные пользователя"),
        ApiResponse(
            responseCode = "401",
            description = "Нет access-токена, он истёк или недействителен (UNAUTHENTICATED) — нужно вызвать /api/auth/refresh",
        ),
        ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
    )
    @GetExchange
    fun getCurrentUser(): UserResponse

    @Operation(
        summary = "Изменить профиль",
        description = "Меняет ник и/или аватар: изменяется только переданное. Ник не уникален. В игровых комнатах " +
            "новый ник появляется через несколько секунд.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Профиль изменён, возвращены обновлённые данные пользователя"),
        ApiResponse(
            responseCode = "400",
            description = "Ничего не передано или ник не проходит требования: 3–32 символа без пробелов по краям (VALIDATION_ERROR)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
    )
    @PatchExchange("/profile")
    fun updateProfile(@Valid @RequestBody request: UpdateProfileRequest): UserResponse

    @Operation(
        summary = "Добавить вход по паролю",
        description = "Для аккаунта без пароля. После этого можно входить по логину аккаунта и паролю.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пароль добавлен, возвращены обновлённые данные пользователя"),
        ApiResponse(responseCode = "400", description = "Пароль не проходит требования (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "409", description = "Пароль уже задан (LOGIN_METHOD_ALREADY_LINKED)"),
    )
    @PostExchange("/login-methods/password")
    fun addPasswordLogin(@Valid @RequestBody request: SetPasswordRequest): UserResponse

    @Operation(
        summary = "Сменить пароль",
        description = "Проверяет текущий пароль и задаёт новый. Все сессии пользователя завершаются: другие устройства " +
            "и вкладки потеряют доступ не позже чем через 15 минут (срок access-токена). Текущий клиент сразу получает " +
            "новую сессию в куках и остаётся в системе.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пароль изменён, куки сессии обновлены, возвращены данные пользователя"),
        ApiResponse(
            responseCode = "400",
            description = "Новый пароль не проходит требования (VALIDATION_ERROR) или текущий пароль неверен " +
                "(INVALID_CURRENT_PASSWORD)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "409", description = "У аккаунта нет пароля (PASSWORD_NOT_SET)"),
    )
    @PutExchange("/login-methods/password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): UserResponse

    @Operation(
        summary = "Привязать Google-аккаунт",
        description = "Явная привязка Google к текущему аккаунту: после неё можно входить через этот Google-аккаунт.",
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
    )
    @PostExchange("/login-methods/google")
    fun addGoogleLogin(@Valid @RequestBody request: GoogleIdTokenRequest): UserResponse
}
