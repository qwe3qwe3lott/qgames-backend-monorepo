package site.qgames.userauth.util

object Nicknames {

    /** Ник для аккаунта без формы регистрации (вход через Google), например `player-a1b2c3`. */
    fun generate(): String = "player-" + Hashing.randomHex(3)
}
