package site.qgames.userauth.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest

/**
 * Аутентификация. Токены возвращаются в теле ответа: превращать их в куки — задача user-gateway.
 */
@Tag(
    name = "auth",
    description = "Аутентификация (для gateway). Внутренние ручки для user-gateway, наружу не публикуются. Токены " +
        "возвращаются в теле ответа, в куки их превращает gateway.",
)
@HttpExchange("/api/auth")
interface AuthApi {

    @Operation(
        summary = "Вход по email и паролю",
        description = "Открывает новую сессию: короткоживущий access-токен и refresh-токен. Пароль добавляется в профиле " +
            "аккаунта, созданного через Google, логин — email этого Google-аккаунта. Сброса пароля нет: забывший пароль " +
            "входит через Google и задаёт новый.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, выданы токены"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Неверный email или пароль (INVALID_CREDENTIALS); так же отвечаем для неизвестного email " +
                "и аккаунта без пароля",
        ),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse

    @Operation(
        summary = "Вход через Google",
        description = "Принимает Google ID token, полученный фронтом через Google Identity Services. Это единственный " +
            "способ создать аккаунт. Если Google-аккаунт уже привязан, открывает сессию его владельца. Иначе создаёт " +
            "новый аккаунт с email из Google (он должен быть подтверждён в Google: это логин для входа по паролю), ником " +
            "вида player-a1b2c3 и аватаром CAT — их можно сменить в профиле. К существующему аккаунту с тем же email " +
            "Google автоматически не привязывается.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, выданы токены"),
        ApiResponse(
            responseCode = "400",
            description = "Невалидный запрос (VALIDATION_ERROR) или email не подтверждён в Google (GOOGLE_EMAIL_NOT_VERIFIED)",
        ),
        ApiResponse(responseCode = "401", description = "ID token недействителен (INVALID_GOOGLE_TOKEN)"),
        ApiResponse(
            responseCode = "409",
            description = "Аккаунт с таким email уже есть, а этот Google-аккаунт к нему не привязан: нужно войти " +
                "и привязать Google явно (ACCOUNT_LINK_REQUIRED)",
        ),
    )
    @PostExchange("/google")
    fun loginWithGoogle(@Valid @RequestBody request: GoogleIdTokenRequest): AuthResponse

    @Operation(
        summary = "Обновление сессии",
        description = "Обменивает refresh-токен на новую пару токенов, предъявленный токен становится использованным. " +
            "Повтор того же токена в течение 10 секунд допускается (параллельные вкладки) и выдаёт ещё одну пару " +
            "той же сессии; более поздний повтор отзывает всю сессию.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Выдана новая пара токенов"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Токен неизвестен или истёк (INVALID_REFRESH_TOKEN); токен уже использован, сессия отозвана " +
                "(REFRESH_TOKEN_REUSED)",
        ),
    )
    @PostExchange("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): AuthResponse

    @Operation(
        summary = "Выход",
        description = "Отзывает сессию, к которой относится refresh-токен. Неизвестный токен игнорируется.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия отозвана"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
    )
    @PostExchange("/logout")
    fun logout(@Valid @RequestBody request: RefreshTokenRequest)
}
