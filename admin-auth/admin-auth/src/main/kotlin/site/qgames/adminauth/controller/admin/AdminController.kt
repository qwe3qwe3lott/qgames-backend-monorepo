package site.qgames.adminauth.controller.admin

import org.springframework.web.bind.annotation.RestController
import site.qgames.adminauth.gatewayapi.api.AdminApi
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import site.qgames.adminauth.mapper.admin.AdminResponseMapper
import site.qgames.adminauth.service.admin.AdminService
import site.qgames.common.web.context.RequestUserContext

/** Id текущего администратора приходит в заголовке X-User-Id от admin-gateway. */
@RestController
class AdminController(
    private val adminService: AdminService,
    private val adminResponseMapper: AdminResponseMapper,
    private val requestUserContext: RequestUserContext,
) : AdminApi {

    override fun getAdmins(): AdminListResponse =
        adminResponseMapper.toResponse(adminService.listAdmins())

    override fun getCurrentAdmin(): AdminResponse =
        adminResponseMapper.toResponse(adminService.getAdmin(requestUserContext.userId()))
}
