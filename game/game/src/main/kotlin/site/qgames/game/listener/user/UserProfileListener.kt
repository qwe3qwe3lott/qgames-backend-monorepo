package site.qgames.game.listener.user

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import site.qgames.game.service.room.RoomService
import site.qgames.userauth.api.event.user.UserProfileChanged

/**
 * Смена ника или аватара в user-auth: событие profile-changed во все комнаты пользователя. Сам профиль game читает
 * из таблицы user (join, только чтение), поэтому слушатель ничего не сохраняет. Событие приходит после коммита
 * транзакции user-auth; каждая комната обрабатывается в своей транзакции под своей блокировкой (seq потока комнаты).
 */
@Component
class UserProfileListener(
    private val roomService: RoomService,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUserProfileChanged(event: UserProfileChanged) {
        roomService.findRoomIdsByUser(event.id).forEach { roomService.profileChanged(it, event.id) }
    }
}
