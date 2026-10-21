package site.qgames.adminauth.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.adminauth.gatewayapi.dto.auth.RefreshTokenRequest

/**
 * Аутентификация администраторов. Токены возвращаются в теле ответа: превращать их в куки — задача admin-gateway.
 */
@Tag(
    name = "Аутентификация администраторов (для gateway)",
    description = "Внутренние ручки для admin-gateway, наружу не публикуются. Токены возвращаются в теле ответа, " +
        "в куки их превращает gateway.",
)
@HttpExchange("/api/auth")
interface AuthApi {

    @Operation(
        summary = "Вход по логину и паролю",
        description = "Открывает новую сессию администратора: access-токен на 5 минут и refresh-токен на 15 минут. " +
            "Логин сравнивается точно, с учётом регистра и без обрезки пробелов. Регистрации нет: администраторов " +
            "заводят вне API.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, выданы токены"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(
            responseCode = "401",
            description = "Неверный логин или пароль (INVALID_CREDENTIALS); так же отвечаем для неизвестного логина",
        ),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse

    @Operation(
        summary = "Обновление сессии",
        description = "Обменивает refresh-токен на новую пару токенов, предъявленный токен становится использованным. " +
            "Новый refresh-токен снова действует 15 минут. Повтор того же токена в течение 10 секунд допускается " +
            "(параллельные вкладки) и выдаёт ещё одну пару той же сессии; более поздний повтор отзывает всю сессию.",
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
        description = "Отзывает сессию, к которой относится refresh-токен. Неизвестный или уже отозванный токен " +
            "игнорируется, поэтому повторный выход безопасен.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия отозвана"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
    )
    @PostExchange("/logout")
    fun logout(@Valid @RequestBody request: RefreshTokenRequest)
}
