package site.qgames.userauth.entity.user

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("user_auth#user")
data class UserEntity(
    @Id
    val id: UUID? = null,
    val email: String,
    val nickname: String,
    val avatar: String,
    val createdAt: Instant,
    /**
     * Оптимистичная блокировка и порядок событий профиля: растёт при каждом изменении строки
     * (в собственных UPDATE-запросах увеличивается вручную).
     */
    @Version
    val version: Long? = null,
)
