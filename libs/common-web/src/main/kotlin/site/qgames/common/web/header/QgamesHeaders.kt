package site.qgames.common.web.header

object QgamesHeaders {
    /**
     * Идентификатор аутентифицированного пользователя. Проставляется только user-gateway после проверки JWT,
     * сервисы доверяют ему, потому что внутренняя сеть закрыта (service mesh).
     */
    const val USER_ID = "X-User-Id"
}
