package site.qgames.games.api.session

/**
 * Перевод объектов в JSON-дерево и обратно (Map, List, String, Number, Boolean, null) — тем же способом, каким сервис
 * пишет журнал и отдаёт данные клиенту. Модули игр сами JSON не знают: реализацию даёт сервис (Jackson), а
 * [SessionEngine] через неё переводит значения событий в дерево и читает документ партии типизированно.
 */
interface SessionCodec {

    /** JSON-дерево значения [value]: как оно будет выглядеть в JSON. */
    fun toTree(value: Any?): Any?

    /** Объект класса [type] из JSON-дерева [tree]. */
    fun <T : Any> fromTree(tree: Any?, type: Class<T>): T
}
