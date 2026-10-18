package site.qgames.userauth.event.auth

data class VerificationCodeIssuedEvent(
    val email: String,
    val code: String,
)
