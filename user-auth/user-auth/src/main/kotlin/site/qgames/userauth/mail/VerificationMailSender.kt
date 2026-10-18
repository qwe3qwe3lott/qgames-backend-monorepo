package site.qgames.userauth.mail

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import site.qgames.userauth.config.AuthProperties

@Component
class VerificationMailSender(
    private val mailSender: JavaMailSender,
    private val properties: AuthProperties,
) {

    fun send(email: String, code: String) {
        val message = SimpleMailMessage().apply {
            from = properties.mail.from
            setTo(email)
            subject = "QGames: код подтверждения"
            text = "Ваш код подтверждения: $code\n\nКод действует ${properties.verification.codeTtl.toMinutes()} минут."
        }
        mailSender.send(message)
    }
}
