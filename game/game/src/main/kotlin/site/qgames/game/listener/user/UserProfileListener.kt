package site.qgames.game.listener.user

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import site.qgames.game.model.replica.UserAvatar
import site.qgames.game.model.replica.UserAuthUserModel
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.userauth.asyncapi.event.UserProfileChanged
import site.qgames.userauth.asyncapi.topic.UserAuthTopics
import java.util.UUID
import site.qgames.userauth.asyncapi.event.Avatar as AvatarProto

/**
 * Реплика user_auth."user" (CDC). Битое сообщение не повторяется, а пропускается (см. libs/common-cdc-consumer),
 * обработка идемпотентна: снапшот применяется, только если он новее сохранённого.
 */
@Component
class UserProfileListener(
    private val userAuthUserRepository: UserAuthUserRepository,
) {

    @KafkaListener(topics = [UserAuthTopics.USER_PROFILE])
    fun onUserProfileChanged(record: ConsumerRecord<String, ByteArray>) {
        val event = UserProfileChanged.parseFrom(record.value())
        val applied = userAuthUserRepository.upsert(
            UserAuthUserModel(
                id = UUID.fromString(event.userId),
                nickname = event.nickname,
                avatar = event.avatar.toModel(),
                version = event.version,
            ),
        )
        if (!applied) {
            log.debug("Skipped stale profile snapshot for user {}", event.userId)
        }
    }

    private fun AvatarProto.toModel(): UserAvatar = when (this) {
        AvatarProto.AVATAR_CAT -> UserAvatar.CAT
        AvatarProto.AVATAR_DOG -> UserAvatar.DOG
        AvatarProto.AVATAR_UNSPECIFIED, AvatarProto.UNRECOGNIZED -> throw IllegalArgumentException("Unknown avatar $this")
    }

    companion object {
        private val log = LoggerFactory.getLogger(UserProfileListener::class.java)
    }
}
