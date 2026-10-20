package site.qgames.admingateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import site.qgames.admingateway.publicapi.dto.admin.AdminListResponse
import site.qgames.admingateway.publicapi.dto.admin.AdminResponse

/**
 * Администраторы. Требует аутентификации (access-токен в куке или заголовке Authorization).
 */
@Tag(
    name = "Администраторы",
    description = "Текущий администратор и список всех администраторов. Требуется аутентификация: кука " +
        "admin_access_token (или заголовок Authorization: Bearer).",
)
@HttpExchange("/api/admins")
interface PublicAdminApi {

    @Operation(
        summary = "Список администраторов",
        description = "Все администраторы по возрастанию даты создания, при равенстве — по id. Ролей нет, все " +
            "администраторы равны.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Список администраторов"),
        ApiResponse(
            responseCode = "401",
            description = "Нет access-токена, он истёк или недействителен (UNAUTHENTICATED) — нужно вызвать /api/auth/refresh",
        ),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange
    fun getAdmins(): AdminListResponse

    @Operation(
        summary = "Текущий администратор",
        description = "Данные администратора текущей сессии. Подходит, чтобы проверить, залогинен ли администратор.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Данные администратора"),
        ApiResponse(
            responseCode = "401",
            description = "Нет access-токена, он истёк или недействителен (UNAUTHENTICATED) — нужно вызвать /api/auth/refresh",
        ),
        ApiResponse(responseCode = "404", description = "Администратор не найден (ADMIN_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Сервис аутентификации недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange("/me")
    fun getCurrentAdmin(): AdminResponse
}
