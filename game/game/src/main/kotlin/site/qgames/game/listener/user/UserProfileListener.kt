package site.qgames.game.listener.user

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import site.qgames.game.model.replica.UserAuthUserModel
import site.qgames.game.model.replica.UserAvatar
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.event.user.UserProfileChanged

/**
 * Реплика `"user_auth#user"` в `"game#user_auth#user"` из события модуля user-auth. Доставку после коммита, повтор
 * незавершённых при старте и отдельную транзакцию даёт [ApplicationModuleListener] (реестр публикаций Spring Modulith).
 * Обработка идемпотентна: снапшот применяется, только если он новее сохранённого.
 *
 * Применённый профиль публикует users-changed во все комнаты пользователя. Блокировки комнат не берутся (решение
 * пользователя): если смена ника попадёт между чтением снапшота и маркером нового подписчика, он её не увидит до
 * следующего события участников — это допустимо.
 */
@Component
class UserProfileListener(
    private val userAuthUserRepository: UserAuthUserRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomEventPublisher: RoomEventPublisher,
) {

    @ApplicationModuleListener
    fun onUserProfileChanged(event: UserProfileChanged) {
        val applied = userAuthUserRepository.upsert(
            UserAuthUserModel(
                id = event.id,
                nickname = event.nickname,
                avatar = event.avatar.toModel(),
                version = event.version,
            ),
        )
        if (!applied) {
            log.debug("Skipped stale profile snapshot for user {}", event.id)
            return
        }
        roomMemberRepository.findRoomIdsByUser(event.id)
            .forEach { roomEventPublisher.publish(it, RoomEventType.USERS_CHANGED) }
    }

    private fun Avatar.toModel(): UserAvatar = UserAvatar.valueOf(name)

    companion object {
        private val log = LoggerFactory.getLogger(UserProfileListener::class.java)
    }
}
