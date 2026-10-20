package site.qgames.admingateway.mapper.auth

import site.qgames.admingateway.publicapi.dto.auth.AdminLoginRequest
import site.qgames.adminauth.gatewayapi.dto.auth.LoginRequest as AdminAuthLoginRequest

/**
 * Маппинг между публичным контрактом admin-gateway и контрактом admin-auth. Внутренние DTO наружу не отдаются.
 */
fun AdminLoginRequest.toAdminAuth() = AdminAuthLoginRequest(login = login, password = password)
