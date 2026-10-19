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
import site.qgames.userauth.gatewayapi.dto.auth.RegisterRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegistrationResponse
import site.qgames.userauth.gatewayapi.dto.auth.ResendCodeRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest

/**
 * Аутентификация. Токены возвращаются в теле ответа: превращать их в куки — задача user-gateway.
 */
@Tag(
    name = "Аутентификация (для gateway)",
    description = "Внутренние ручки для user-gateway, наружу не публикуются. Токены возвращаются в теле ответа, " +
        "в куки их превращает gateway.",
)
@HttpExchange("/api/auth")
interface AuthApi {

    @Operation(
        summary = "Регистрация по email и паролю",
        description = "Создаёт неподтверждённый аккаунт со способом входа «пароль» и отправляет на email 6-значный код " +
            "подтверждения. Email приводится к нижнему регистру. Ник (3–32 символа, не уникален) и аватар задаются сразу. " +
            "Войти можно только после подтверждения email.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Аккаунт создан, письмо с кодом отправлено"),
        ApiResponse(responseCode = "400", description = "Невалидный email или пароль (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "409", description = "Email уже зарегистрирован (EMAIL_ALREADY_REGISTERED)"),
    )
    @PostExchange("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): RegistrationResponse

    @Operation(
        summary = "Подтверждение email кодом из письма",
        description = "Проверяет код, помечает email подтверждённым и сразу открывает сессию. Код действует 15 минут, " +
            "на один код даётся 5 попыток.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Email подтверждён, выданы токены новой сессии"),
        ApiResponse(
            responseCode = "400",
            description = "Невалидный запрос (VALIDATION_ERROR), неверный код или неизвестный email " +
                "(INVALID_VERIFICATION_CODE), код истёк (VERIFICATION_CODE_EXPIRED)",
        ),
        ApiResponse(responseCode = "409", description = "Email уже подтверждён (EMAIL_ALREADY_VERIFIED)"),
        ApiResponse(responseCode = "429", description = "Попытки ввода кода исчерпаны (TOO_MANY_VERIFICATION_ATTEMPTS)"),
    )
    @PostExchange("/verify-email")
    fun verifyEmail(@Valid @RequestBody request: VerifyEmailRequest): AuthResponse

    @Operation(
        summary = "Повторная отправка кода подтверждения",
        description = "Выпускает новый код, прежний перестаёт действовать. Для неизвестного или уже подтверждённого email " +
            "письмо не отправляется, ответ — 200.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Запрос принят"),
        ApiResponse(responseCode = "400", description = "Невалидный email (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "429", description = "Код отправлялся меньше минуты назад (RESEND_COOLDOWN)"),
    )
    @PostExchange("/resend-code")
    fun resendCode(@Valid @RequestBody request: ResendCodeRequest)

    @Operation(
        summary = "Вход по email и паролю",
        description = "Открывает новую сессию: короткоживущий access-токен и refresh-токен.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, выданы токены"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Неверный email или пароль (INVALID_CREDENTIALS); так же отвечаем для неизвестного email " +
                "и аккаунта без пароля",
        ),
        ApiResponse(responseCode = "403", description = "Email не подтверждён (EMAIL_NOT_VERIFIED)"),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse

    @Operation(
        summary = "Вход через Google",
        description = "Принимает Google ID token, полученный фронтом через Google Identity Services. Если этот " +
            "Google-аккаунт ещё не привязан, создаёт новый аккаунт или привязывает Google к существующему аккаунту " +
            "с тем же email — только если email подтверждён и у нас, и в Google. Новому аккаунту выдаётся ник вида " +
            "player-a1b2c3 и аватар CAT, их можно сменить в профиле.",
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
            description = "Аккаунт с таким email уже есть, но привязать Google автоматически нельзя — нужно войти " +
                "и привязать явно (ACCOUNT_LINK_REQUIRED)",
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
