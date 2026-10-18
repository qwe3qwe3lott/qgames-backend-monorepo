package site.qgames.userauth.listener.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import site.qgames.userauth.event.auth.VerificationCodeIssuedEvent
import site.qgames.userauth.mail.VerificationMailSender

/**
 * Письмо уходит только после коммита: код не отправится, если транзакция откатилась.
 * Ошибка отправки не ломает запрос — пользователь может запросить код повторно.
 */
@Component
class VerificationCodeMailListener(
    private val verificationMailSender: VerificationMailSender,
) {

    @TransactionalEventListener
    fun onVerificationCodeIssued(event: VerificationCodeIssuedEvent) {
        try {
            verificationMailSender.send(event.email, event.code)
        } catch (ex: Exception) {
            log.error("Failed to send verification code to {}", event.email, ex)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VerificationCodeMailListener::class.java)
    }
}
