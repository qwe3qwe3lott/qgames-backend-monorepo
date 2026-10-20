package site.qgames.adminauth.repository.admin

import org.springframework.stereotype.Repository
import site.qgames.adminauth.mapper.admin.AdminEntityMapper
import site.qgames.adminauth.model.admin.AdminModel
import java.util.UUID

@Repository
class AdminRepository(
    private val adminPostgresqlRepository: AdminPostgresqlRepository,
    private val adminEntityMapper: AdminEntityMapper,
) {

    fun findById(id: UUID): AdminModel? =
        adminPostgresqlRepository.findById(id).orElse(null)?.let(adminEntityMapper::toModel)

    /** Точное совпадение логина, с учётом регистра. */
    fun findByLogin(login: String): AdminModel? =
        adminPostgresqlRepository.findByLogin(login)?.let(adminEntityMapper::toModel)

    /** Все администраторы по возрастанию даты создания, при равенстве — по id. */
    fun findAll(): List<AdminModel> =
        adminPostgresqlRepository.findAllOrderByCreatedAt().map(adminEntityMapper::toModel)

    /** Создаёт администратора, только если их ещё нет. Возвращает true, если администратор создан. */
    fun insertIfNoAdmins(login: String, passwordHash: String): Boolean =
        adminPostgresqlRepository.insertIfNoAdmins(login, passwordHash) == 1
}
