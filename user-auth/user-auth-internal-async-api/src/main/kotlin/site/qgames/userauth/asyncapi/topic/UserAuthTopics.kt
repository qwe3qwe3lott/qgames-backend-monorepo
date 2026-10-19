package site.qgames.userauth.asyncapi.topic

/**
 * Асинхронные контракты user-auth. Топик формирует Debezium EventRouter по шаблону `outbox.event.<aggregate_type>`.
 */
object UserAuthTopics {

    /** Значение `outbox_event.aggregate_type` для событий профиля. */
    const val USER_PROFILE_AGGREGATE = "user-auth.user-profile"

    /** Топик с событиями `UserProfileChanged`, ключ — id пользователя. */
    const val USER_PROFILE = "outbox.event.$USER_PROFILE_AGGREGATE"

    const val USER_PROFILE_CHANGED_EVENT = "UserProfileChanged"
}
