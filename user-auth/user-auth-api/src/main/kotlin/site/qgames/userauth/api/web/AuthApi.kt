package site.qgames.userauth.api.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.auth.GoogleRegisterRequest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.UserResponse

/**
 * Аутентификация игроков. Токены в контракт не входят: контроллер кладёт их в HttpOnly-куки
 * (access_token, refresh_token), refresh и logout читают refresh-токен из куки.
 */
@Tag(
    name = "auth",
    description = "Аутентификация. Регистрация только через внешнего провайдера (Google) с выбором логина, вход по " +
        "логину и паролю или через привязанный Google, обновление сессии и выход. Токены выдаются только в " +
        "HttpOnly-куках access_token и refresh_token, в теле ответа их нет.",
)
@HttpExchange("/api/auth")
interface AuthApi {

    @Operation(
        summary = "Вход по логину и паролю",
        description = "Открывает сессию: ставит куки access_token (15 минут) и refresh_token (30 дней). Логин " +
            "сравнивается без учёта регистра и пробелов по краям. Пароль задаётся при регистрации или позже в " +
            "профиле. Сброса пароля нет: забывший пароль входит через Google и задаёт новый.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Неверный логин или пароль (INVALID_CREDENTIALS)"),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): UserResponse

    @Operation(
        summary = "Вход через Google",
        description = "Принимает Google ID token из Google Identity Services и открывает сессию аккаунта, к которому " +
            "привязан этот Google-аккаунт. Аккаунт не создаётся: для нового Google-аккаунта — 404 " +
            "REGISTRATION_REQUIRED, тогда клиент спрашивает логин и вызывает /api/auth/google/register с тем же токеном.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "ID token недействителен (INVALID_GOOGLE_TOKEN)"),
        ApiResponse(
            responseCode = "404",
            description = "Google-аккаунт не привязан ни к одному аккаунту, нужна регистрация (REGISTRATION_REQUIRED)",
        ),
    )
    @PostExchange("/google/login")
    fun loginWithGoogle(@Valid @RequestBody request: GoogleIdTokenRequest): UserResponse

    @Operation(
        summary = "Регистрация через Google",
        description = "Создаёт аккаунт, привязанный к Google-аккаунту из ID token, и открывает сессию. Логин задаётся " +
            "один раз и не меняется; ник сначала равен логину, аватар — CAT, их можно сменить в профиле. Если передан " +
            "пароль, сразу добавляется вход по логину и паролю.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Аккаунт создан, вход выполнен, возвращены данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос: логин или пароль не по правилам (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "ID token недействителен (INVALID_GOOGLE_TOKEN)"),
        ApiResponse(
            responseCode = "409",
            description = "Логин занят, в том числе одновременной регистрацией (LOGIN_TAKEN); этот Google-аккаунт уже " +
                "зарегистрирован, в том числе одновременным запросом, — нужно войти (ACCOUNT_ALREADY_EXISTS)",
        ),
    )
    @PostExchange("/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerWithGoogle(@Valid @RequestBody request: GoogleRegisterRequest): UserResponse

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
