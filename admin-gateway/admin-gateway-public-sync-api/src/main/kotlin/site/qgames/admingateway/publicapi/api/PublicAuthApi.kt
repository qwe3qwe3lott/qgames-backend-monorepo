package site.qgames.admingateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.admingateway.publicapi.dto.admin.AdminResponse
import site.qgames.admingateway.publicapi.dto.auth.AdminLoginRequest

/**
 * Аутентификация администраторов. Токены в контракт не входят: gateway кладёт их в HttpOnly-куки
 * (admin_access_token, admin_refresh_token), refresh и logout читают refresh-токен из куки.
 */
@Tag(
    name = "Аутентификация",
    description = "Вход и выход администратора. Регистрации нет. Токены выдаются только в HttpOnly-куках " +
        "admin_access_token и admin_refresh_token, в теле ответа их нет.",
)
@HttpExchange("/api/auth")
interface PublicAuthApi {

    @Operation(
        summary = "Вход по логину и паролю",
        description = "Открывает сессию администратора: ставит куки admin_access_token (5 минут) и admin_refresh_token " +
            "(15 минут, продлевается при каждом обновлении сессии). Логин сравнивается точно, с учётом регистра и без " +
            "обрезки пробелов.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Вход выполнен, возвращены данные администратора"),
        ApiResponse(responseCode = "400", description = "Невалидный запрос (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Неверный логин или пароль (INVALID_CREDENTIALS)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/login")
    fun login(@Valid @RequestBody request: AdminLoginRequest): AdminResponse

    @Operation(
        summary = "Обновление сессии",
        description = "Берёт refresh-токен из куки admin_refresh_token и ставит новые куки; новый refresh-токен снова " +
            "действует 15 минут. Вызывать, когда access-токен истёк (защищённые ручки ответили 401). Параллельные " +
            "вызовы из нескольких вкладок в течение 10 секунд допустимы. При ошибке обе куки удаляются.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия продлена, возвращены данные администратора"),
        ApiResponse(
            responseCode = "401",
            description = "Куки нет, токен неизвестен или истёк (INVALID_REFRESH_TOKEN); токен уже использован, " +
                "сессия отозвана (REFRESH_TOKEN_REUSED)",
        ),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/refresh")
    fun refresh(): AdminResponse

    @Operation(
        summary = "Выход",
        description = "Отзывает сессию по куке admin_refresh_token и удаляет обе куки. Без куки просто удаляет куки.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Сессия завершена, куки удалены"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/logout")
    fun logout()
}
