package site.qgames.userauth.api.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.UserResponse

/**
 * Аутентификация игроков. Токены в контракт не входят: контроллер кладёт их в HttpOnly-куки
 * (access_token, refresh_token), refresh и logout читают refresh-токен из куки.
 */
@Tag(
    name = "auth",
    description = "Аутентификация. Вход через Google (он же создаёт аккаунт) и по email и паролю, обновление сессии " +
        "и выход. Токены выдаются только в HttpOnly-куках access_token и refresh_token, в теле ответа их нет.",
)
@HttpExchange("/api/auth")
interface AuthApi {

    @Operation(
        summary = "Вход по email и паролю",
        description = "Открывает сессию: ставит куки access_token (15 минут) и refresh_token (30 дней). Пароль " +
            "задаётся в профиле аккаунта, созданного через Google, логин — email этого Google-аккаунта. Сброса пароля " +
            "нет: забывший пароль входит через Google и задаёт новый.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Неверный email или пароль (INVALID_CREDENTIALS)"),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): UserResponse

    @Operation(
        summary = "Вход через Google",
        description = "Принимает Google ID token из Google Identity Services. Это единственный способ создать аккаунт: " +
            "новый Google-аккаунт создаёт аккаунт с email из Google (он должен быть подтверждён в Google: это логин для " +
            "входа по паролю), ником вида player-a1b2c3 и аватаром CAT — их можно сменить в профиле. К существующему " +
            "аккаунту с тем же email Google автоматически не привязывается.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные пользователя"),
        ApiResponse(
            responseCode = "400",
            description = "Невалидный запрос (VALIDATION_ERROR) или email не подтверждён в Google (GOOGLE_EMAIL_NOT_VERIFIED)",
        ),
        ApiResponse(responseCode = "401", description = "ID token недействителен (INVALID_GOOGLE_TOKEN)"),
        ApiResponse(
            responseCode = "409",
            description = "Аккаунт с таким email уже есть, а этот Google-аккаунт к нему не привязан: нужно войти и " +
                "привязать Google явно (ACCOUNT_LINK_REQUIRED)",
        ),
    )
    @PostExchange("/google")
    fun loginWithGoogle(@Valid @RequestBody request: GoogleIdTokenRequest): UserResponse

    @Operation(
        summary = "Обновление сессии",
        description = "Берёт refresh-токен из куки refresh_token и ставит новые куки. Вызывать, когда access-токен " +
            "истёк (защищённые ручки ответили 401). Параллельные вызовы из нескольких вкладок в течение 10 секунд " +
            "допустимы. При ошибке обе куки удаляются.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия продлена, возвращены данные пользователя"),
        ApiResponse(
            responseCode = "401",
            description = "Куки нет, токен неизвестен или истёк (INVALID_REFRESH_TOKEN); токен уже использован, " +
                "сессия отозвана (REFRESH_TOKEN_REUSED)",
        ),
    )
    @PostExchange("/refresh")
    fun refresh(): UserResponse

    @Operation(
        summary = "Выход",
        description = "Отзывает сессию по куке refresh_token и удаляет обе куки. Без куки просто удаляет куки.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия завершена, куки удалены"),
    )
    @PostExchange("/logout")
    fun logout()
}
