package site.qgames.adminauth.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse

/**
 * Администраторы. Текущий администратор определяется по заголовку X-User-Id, который ставит admin-gateway.
 */
@Tag(
    name = "Администраторы (для gateway)",
    description = "Внутренние ручки для admin-gateway. Текущий администратор определяется по заголовку X-User-Id, " +
        "который gateway ставит из проверенного JWT.",
)
@HttpExchange("/api/admins")
interface AdminApi {

    @Operation(
        summary = "Список администраторов",
        description = "Все администраторы по возрастанию даты создания, при равенстве — по id. Ролей нет, все " +
            "администраторы равны. Доступ проверяет admin-gateway.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Список администраторов"),
    )
    @GetExchange
    fun getAdmins(): AdminListResponse

    @Operation(
        summary = "Текущий администратор",
        description = "Данные администратора, чей id пришёл в заголовке X-User-Id.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Данные администратора"),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Администратор не найден (ADMIN_NOT_FOUND)"),
    )
    @GetExchange("/me")
    fun getCurrentAdmin(): AdminResponse
}
