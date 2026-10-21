package site.qgames.admingateway.controller.admin

import org.springframework.web.bind.annotation.RestController
import site.qgames.adminauth.gatewayapi.api.AdminApi
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import site.qgames.admingateway.publicapi.api.PublicAdminApi

/**
 * Требует аутентификации. X-User-Id в admin-auth проставляет UserIdPropagationInterceptor. Ответы — DTO контракта
 * admin-auth, они передаются как есть.
 */
@RestController
class AdminGatewayController(
    private val adminApi: AdminApi,
) : PublicAdminApi {

    override fun getAdmins(): AdminListResponse = adminApi.getAdmins()

    override fun getCurrentAdmin(): AdminResponse = adminApi.getCurrentAdmin()
}
