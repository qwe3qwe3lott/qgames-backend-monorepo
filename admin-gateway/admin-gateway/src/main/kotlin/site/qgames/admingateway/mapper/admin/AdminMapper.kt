package site.qgames.admingateway.mapper.admin

import site.qgames.admingateway.publicapi.dto.admin.AdminListResponse
import site.qgames.admingateway.publicapi.dto.admin.AdminResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse as AdminAuthAdminListResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse as AdminAuthAdminResponse

/**
 * Маппинг между публичным контрактом admin-gateway и контрактом admin-auth. Внутренние DTO наружу не отдаются.
 */
fun AdminAuthAdminResponse.toPublic() = AdminResponse(id = id, login = login, createdAt = createdAt)

fun AdminAuthAdminListResponse.toPublic() = AdminListResponse(admins = admins.map { it.toPublic() })
