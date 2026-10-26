package site.qgames.adminauth.mapper.admin

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.adminauth.model.admin.AdminModel
import site.qgames.adminauth.table.admin.AdminTable

/** Строки таблицы `admin` → модели. */
@Component
class AdminRowMapper {

    fun toAdmin(row: ResultRow) = AdminModel(
        id = row[AdminTable.id],
        login = row[AdminTable.login],
        passwordHash = row[AdminTable.passwordHash],
        createdAt = row[AdminTable.createdAt],
    )
}
