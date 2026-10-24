package site.qgames.adminauth.mapper.admin

import org.springframework.stereotype.Component
import site.qgames.adminauth.api.dto.admin.AdminListResponse
import site.qgames.adminauth.api.dto.admin.AdminResponse
import site.qgames.adminauth.model.admin.AdminModel

/** Модели администраторов → DTO контракта. Хеш пароля наружу не попадает. */
@Component
class AdminResponseMapper {

    fun toResponse(admin: AdminModel) = AdminResponse(
        id = admin.id,
        login = admin.login,
        createdAt = admin.createdAt,
    )

    fun toResponse(admins: List<AdminModel>) = AdminListResponse(admins = admins.map(::toResponse))
}
