package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.games.core.SessionCodec
import tools.jackson.databind.json.JsonMapper

/**
 * JSON-дерево для платформы партий (SessionEngine) — тем же JsonMapper, которым пишется журнал и поток. Дерево
 * строится через JSON-строку: значения получаются ровно такими, какими их прочитает журнал (даты — строки ISO, id —
 * строки, числа — Integer/Long/Double), и свёртка свежих событий совпадает со свёрткой прочитанных из БД.
 */
@Component
class SessionJsonCodec(
    private val jsonMapper: JsonMapper,
) : SessionCodec {

    override fun toTree(value: Any?): Any? = jsonMapper.readValue(jsonMapper.writeValueAsString(value), Any::class.java)

    override fun <T : Any> fromTree(tree: Any?, type: Class<T>): T = jsonMapper.convertValue(tree, type)
}
