package site.qgames.adminauth.model.admin

import java.time.Instant
import java.util.UUID

/** Администратор. Ролей нет, все администраторы равны. */
data class AdminModel(
    val id: UUID,
    /** Уникален, сравнивается точно (с учётом регистра). */
    val login: String,
    /** Argon2id, соль внутри хеша. Наружу не отдаётся. */
    val passwordHash: String,
    val createdAt: Instant,
)
