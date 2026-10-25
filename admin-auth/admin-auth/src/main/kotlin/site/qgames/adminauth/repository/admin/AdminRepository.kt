package site.qgames.adminauth.repository.admin

import org.springframework.stereotype.Repository
import site.qgames.adminauth.model.admin.AdminModel
import java.time.Instant
import java.util.UUID

@Repository
class AdminRepository(
    private val adminExposedRepository: AdminExposedRepository,
) {

    fun findById(id: UUID): AdminModel? = adminExposedRepository.findById(id)

    /** Точное совпадение логина, с учётом регистра. */
    fun findByLogin(login: String): AdminModel? = adminExposedRepository.findByLogin(login)

    /** Все администраторы по возрастанию даты создания, при равенстве — по id. */
    fun findAll(): List<AdminModel> = adminExposedRepository.findAll()

    /** Создаёт администратора, только если их ещё нет. Возвращает true, если администратор создан. */
    fun insertIfNoAdmins(login: String, passwordHash: String, now: Instant): Boolean =
        adminExposedRepository.insertIfNoAdmins(login, passwordHash, now)
}
