package site.qgames.userauth.api.cookie

/**
 * Куки сессии игрока — часть контракта с фронтом портала. Ставит их контроллер аутентификации user-auth,
 * access-токен читает Security-цепочка портала в app.
 */
object AuthCookieNames {
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"

    /** Access-кука уходит на все пути. */
    const val ACCESS_TOKEN_PATH = "/"

    /** Эндпоинты аутентификации: refresh-кука уходит только на них, access-токен на них не читается. */
    const val AUTH_PATH = "/api/auth"
}
