package site.qgames.adminauth.mapper.admin

import org.springframework.stereotype.Component
import site.qgames.adminauth.entity.admin.AdminEntity
import site.qgames.adminauth.model.admin.AdminModel

@Component
class AdminEntityMapper {

    fun toModel(entity: AdminEntity) = AdminModel(
        id = entity.id!!,
        login = entity.login,
        passwordHash = entity.passwordHash,
        createdAt = entity.createdAt,
    )
}
