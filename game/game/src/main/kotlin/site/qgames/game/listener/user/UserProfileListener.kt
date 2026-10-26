package site.qgames.game.listener.user

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.userauth.api.event.user.UserProfileChanged

/**
 * Смена ника или аватара в user-auth: публикует users-changed во все комнаты пользователя. Сам профиль game читает
 * из таблицы user (join, только чтение), поэтому слушатель ничего не сохраняет. Событие приходит после коммита
 * транзакции user-auth; комнаты читаются в своей (новой) транзакции — прежняя уже завершена.
 *
 * Блокировки комнат не берутся (решение пользователя): если смена ника попадёт между чтением снапшота и маркером
 * нового подписчика, он её не увидит до следующего события участников — это допустимо.
 */
@Component
class UserProfileListener(
    private val roomMemberRepository: RoomMemberRepository,
    private val roomEventPublisher: RoomEventPublisher,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun onUserProfileChanged(event: UserProfileChanged) {
        roomMemberRepository.findRoomIdsByUser(event.id)
            .forEach { roomEventPublisher.publish(it, RoomEventType.USERS_CHANGED) }
    }
}
