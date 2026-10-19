package site.qgames.userauth.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

object Hashing {

    private val secureRandom = SecureRandom()

    fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    fun randomUrlSafeToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun randomHex(bytes: Int): String =
        HexFormat.of().formatHex(ByteArray(bytes).also(secureRandom::nextBytes))

    fun randomDigits(length: Int): String =
        (1..length).joinToString("") { secureRandom.nextInt(10).toString() }
}
