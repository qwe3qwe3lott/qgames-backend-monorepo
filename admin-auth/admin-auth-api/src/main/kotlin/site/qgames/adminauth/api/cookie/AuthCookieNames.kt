package site.qgames.adminauth.api.cookie

/**
 * Куки сессии администратора — часть контракта с фронтом админки. Ставит их контроллер аутентификации admin-auth,
 * access-токен читает Security-цепочка админки в app. Имена отличаются от кук игрока: оба фронта ходят в одно
 * приложение, и одноимённые куки перетёрли бы друг друга.
 */
object AuthCookieNames {
    const val ACCESS_TOKEN = "admin_access_token"
    const val REFRESH_TOKEN = "admin_refresh_token"

    /** Все ручки админки — под этим префиксом, access-кука уходит только на них. */
    const val ADMIN_PATH = "/api/admin"

    /** Эндпоинты аутентификации: refresh-кука уходит только на них, access-токен на них не читается. */
    const val AUTH_PATH = "/api/admin/auth"
}
