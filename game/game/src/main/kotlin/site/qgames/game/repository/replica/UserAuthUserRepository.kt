package site.qgames.game.repository.replica

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.replica.UserAuthUserEntityMapper
import site.qgames.game.model.replica.UserAuthUserModel
import java.util.UUID

@Repository
class UserAuthUserRepository(
    private val userAuthUserPostgresqlRepository: UserAuthUserPostgresqlRepository,
    private val userAuthUserEntityMapper: UserAuthUserEntityMapper,
) {

    /**
     * Применяет снапшот, только если его версия больше сохранённой: повторная доставка и перестановка событий безопасны.
     * Возвращает true, если строка вставлена или обновлена.
     */
    fun upsert(user: UserAuthUserModel): Boolean = userAuthUserPostgresqlRepository.upsert(userAuthUserEntityMapper.toEntity(user))

    fun findByIds(ids: Collection<UUID>): Map<UUID, UserAuthUserModel> {
        if (ids.isEmpty()) return emptyMap()
        return userAuthUserPostgresqlRepository.findByIds(ids).map(userAuthUserEntityMapper::toModel).associateBy { it.id }
    }
}
