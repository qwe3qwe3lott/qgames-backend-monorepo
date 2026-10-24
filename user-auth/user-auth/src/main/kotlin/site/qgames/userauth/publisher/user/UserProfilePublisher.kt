package site.qgames.userauth.publisher.user

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.event.user.UserProfileChanged
import site.qgames.userauth.model.user.UserModel

/**
 * Публикует снапшот публичного профиля событием модуля [UserProfileChanged]. Вызывается в транзакции, которая создала
 * или изменила профиль, с уже сохранённой моделью: её version уходит в событие и определяет порядок применения у
 * потребителей. Spring Modulith записывает публикацию в реестр в той же транзакции и доставляет её слушателям после
 * коммита; незавершённые доставки переотправляются при старте.
 */
@Component
class UserProfilePublisher(
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(user: UserModel) {
        eventPublisher.publishEvent(
            UserProfileChanged(
                id = user.id,
                nickname = user.nickname,
                avatar = Avatar.valueOf(user.avatar.name),
                version = user.version,
            ),
        )
    }
}
