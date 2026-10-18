package site.qgames.usergateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.auth.LoginRequest
import site.qgames.usergateway.publicapi.dto.auth.RegisterRequest
import site.qgames.usergateway.publicapi.dto.auth.RegistrationResponse
import site.qgames.usergateway.publicapi.dto.auth.ResendCodeRequest
import site.qgames.usergateway.publicapi.dto.auth.VerifyEmailRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse

/**
 * Публичная аутентификация. Токены в контракт не входят: gateway кладёт их в HttpOnly-куки
 * (access_token, refresh_token), refresh и logout читают refresh-токен из куки.
 */
@Tag(
    name = "Аутентификация",
    description = "Регистрация, подтверждение email, вход и выход. Токены выдаются только в HttpOnly-куках " +
        "access_token и refresh_token, в теле ответа их нет.",
)
@HttpExchange("/api/auth")
interface PublicAuthApi {

    @Operation(
        summary = "Регистрация по email и паролю",
        description = "Создаёт аккаунт и отправляет на email 6-значный код подтверждения. Сессия не открывается: " +
            "войти можно после подтверждения email.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Аккаунт создан, письмо с кодом отправлено"),
        ApiResponse(responseCode = "400", description = "Невалидный email или пароль (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "409", description = "Email уже зарегистрирован (EMAIL_ALREADY_REGISTERED)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): RegistrationResponse

    @Operation(
        summary = "Подтверждение email кодом из письма",
        description = "Подтверждает email и сразу открывает сессию: ставит куки с токенами. Код действует 15 минут, " +
            "на один код даётся 5 попыток.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Email подтверждён, сессия открыта, возвращены данные пользователя"),
        ApiResponse(
            responseCode = "400",
            description = "Невалидный запрос (VALIDATION_ERROR), неверный код или неизвестный email " +
                "(INVALID_VERIFICATION_CODE), код истёк (VERIFICATION_CODE_EXPIRED)",
        ),
        ApiResponse(responseCode = "409", description = "Email уже подтверждён (EMAIL_ALREADY_VERIFIED)"),
        ApiResponse(responseCode = "429", description = "Попытки ввода кода исчерпаны (TOO_MANY_VERIFICATION_ATTEMPTS)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/verify-email")
    fun verifyEmail(@Valid @RequestBody request: VerifyEmailRequest): UserResponse

    @Operation(
        summary = "Повторная отправка кода подтверждения",
        description = "Высылает новый код, прежний перестаёт действовать. Для неизвестного или уже подтверждённого " +
            "email письмо не отправляется, ответ — 200.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Запрос принят"),
        ApiResponse(responseCode = "400", description = "Невалидный email (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "429", description = "Код отправлялся меньше минуты назад (RESEND_COOLDOWN)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/resend-code")
    fun resendCode(@Valid @RequestBody request: ResendCodeRequest)

    @Operation(
        summary = "Вход по email и паролю",
        description = "Открывает сессию: ставит куки access_token (15 минут) и refresh_token (30 дней).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные пользователя"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Неверный email или пароль (INVALID_CREDENTIALS)"),
        ApiResponse(responseCode = "403", description = "Email не подтверждён (EMAIL_NOT_VERIFIED)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): UserResponse

    @Operation(
        summary = "Вход через Google",
        description = "Принимает Google ID token из Google Identity Services. Новый Google-аккаунт создаёт аккаунт " +
            "или привязывается к существующему с тем же email, если email подтверждён и у нас, и в Google.",
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
            description = "Аккаунт с таким email уже есть: нужно войти и привязать Google явно (ACCOUNT_LINK_REQUIRED)",
        ),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
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
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/refresh")
    fun refresh(): UserResponse

    @Operation(
        summary = "Выход",
        description = "Отзывает сессию по куке refresh_token и удаляет обе куки. Без куки просто удаляет куки.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия завершена, куки удалены"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/logout")
    fun logout()
}
