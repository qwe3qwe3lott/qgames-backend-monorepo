package site.qgames.userauth.model.user

/** Пользователь вместе с привязанными способами входа. */
data class UserDetailsModel(
    val user: UserModel,
    val loginMethods: List<IdentityProvider>,
)
