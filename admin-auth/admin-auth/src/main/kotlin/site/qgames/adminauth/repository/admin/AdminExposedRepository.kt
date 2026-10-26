package site.qgames.adminauth.repository.admin

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.adminauth.mapper.admin.AdminRowMapper
import site.qgames.adminauth.model.admin.AdminModel
import site.qgames.adminauth.table.admin.AdminTable
import site.qgames.adminauth.util.newId
import java.time.Instant
import java.util.UUID

/** Таблица `admin` (Exposed). Используется только из [AdminRepository]. */
@Repository
@Transactional
class AdminExposedRepository(private val adminRowMapper: AdminRowMapper) {

    fun findById(id: UUID): AdminModel? =
        AdminTable.selectAll().where { AdminTable.id eq id }.singleOrNull()?.let(adminRowMapper::toAdmin)

    fun findByLogin(login: String): AdminModel? =
        AdminTable.selectAll().where { AdminTable.login eq login }.singleOrNull()?.let(adminRowMapper::toAdmin)

    fun findAll(): List<AdminModel> =
        AdminTable.selectAll()
            .orderBy(AdminTable.createdAt to SortOrder.ASC, AdminTable.id to SortOrder.ASC)
            .map(adminRowMapper::toAdmin)

    /**
     * Вставляет администратора, только если таблица пуста (проверка и вставка — в одной транзакции). Бутстрап идёт
     * при старте единственного инстанса; одновременную вставку того же логина отсечёт уникальность `login`.
     * Возвращает true, если администратор создан.
     */
    fun insertIfNoAdmins(login: String, passwordHash: String, now: Instant): Boolean {
        if (!AdminTable.selectAll().limit(1).empty()) {
            return false
        }
        AdminTable.insert {
            it[AdminTable.id] = newId()
            it[AdminTable.login] = login
            it[AdminTable.passwordHash] = passwordHash
            it[AdminTable.createdAt] = now
        }
        return true
    }
}
