package site.qgames.userauth.publisher.user

import org.springframework.stereotype.Component
import site.qgames.common.cdc.source.publisher.OutboxPublisher
import site.qgames.userauth.asyncapi.event.UserProfileChanged
import site.qgames.userauth.asyncapi.topic.UserAuthTopics
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.asyncapi.event.Avatar as AvatarProto

/**
 * Публикует снапшот публичного профиля через outbox. Вызывается в транзакции, которая создала или изменила профиль,
 * с уже сохранённой сущностью: её version уходит в событие и определяет порядок применения у потребителей.
 */
@Component
class UserProfilePublisher(
    private val outboxPublisher: OutboxPublisher,
) {

    fun publish(user: UserModel) {
        val event = UserProfileChanged.newBuilder()
            .setUserId(user.id.toString())
            .setNickname(user.nickname)
            .setAvatar(user.avatar.toProto())
            .setVersion(user.version)
            .build()
        outboxPublisher.publish(
            aggregateType = UserAuthTopics.USER_PROFILE_AGGREGATE,
            aggregateId = user.id.toString(),
            eventType = UserAuthTopics.USER_PROFILE_CHANGED_EVENT,
            payload = event,
        )
    }

    private fun UserAvatar.toProto(): AvatarProto = when (this) {
        UserAvatar.CAT -> AvatarProto.AVATAR_CAT
        UserAvatar.DOG -> AvatarProto.AVATAR_DOG
    }
}
