package site.qgames.adminauth.service.admin

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.adminauth.exception.admin.AdminErrors
import site.qgames.adminauth.model.admin.AdminModel
import site.qgames.adminauth.repository.admin.AdminRepository
import java.util.UUID

@Service
class AdminService(
    private val adminRepository: AdminRepository,
) {

    @Transactional(readOnly = true)
    fun getAdmin(adminId: UUID): AdminModel =
        adminRepository.findById(adminId) ?: throw AdminErrors.adminNotFound()

    /** Все администраторы по возрастанию даты создания, при равенстве — по id. */
    @Transactional(readOnly = true)
    fun listAdmins(): List<AdminModel> = adminRepository.findAll()
}
