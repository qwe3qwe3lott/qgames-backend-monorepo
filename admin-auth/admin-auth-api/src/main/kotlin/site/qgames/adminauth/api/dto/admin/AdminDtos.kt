package site.qgames.adminauth.api.dto.admin

import java.time.Instant
import java.util.UUID

data class AdminResponse(
    val id: UUID,
    /** Логин для входа, уникален. */
    val login: String,
    val createdAt: Instant,
)

/** Администраторы по возрастанию даты создания, при равенстве — по id. */
data class AdminListResponse(
    val admins: List<AdminResponse>,
)
