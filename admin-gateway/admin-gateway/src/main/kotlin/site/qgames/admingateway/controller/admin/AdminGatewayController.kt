package site.qgames.admingateway.controller.admin

import org.springframework.web.bind.annotation.RestController
import site.qgames.adminauth.gatewayapi.api.AdminApi
import site.qgames.admingateway.mapper.admin.toPublic
import site.qgames.admingateway.publicapi.api.PublicAdminApi
import site.qgames.admingateway.publicapi.dto.admin.AdminListResponse
import site.qgames.admingateway.publicapi.dto.admin.AdminResponse

/** Требует аутентификации. X-User-Id в admin-auth проставляет UserIdPropagationInterceptor. */
@RestController
class AdminGatewayController(
    private val adminApi: AdminApi,
) : PublicAdminApi {

    override fun getAdmins(): AdminListResponse = adminApi.getAdmins().toPublic()

    override fun getCurrentAdmin(): AdminResponse = adminApi.getCurrentAdmin().toPublic()
}
