package site.qgames.userauth.publisher.user

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.event.user.UserProfileChanged
import site.qgames.userauth.model.user.UserModel

/**
 * Публикует публичный профиль событием модуля [UserProfileChanged]. Вызывается в транзакции, которая создала или
 * изменила профиль, с уже сохранённой моделью. Слушатели других модулей получают событие после коммита
 * (`@TransactionalEventListener(AFTER_COMMIT)`); при откате оно не доставляется.
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
            ),
        )
    }
}
