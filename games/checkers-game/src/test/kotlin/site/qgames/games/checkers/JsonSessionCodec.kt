package site.qgames.games.checkers

import site.qgames.games.api.session.SessionCodec
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** Кодек для тестов модуля: тот же Jackson, что в сервисе game, перевод в дерево — через JSON-строку. */
class JsonSessionCodec : SessionCodec {

    private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    override fun toTree(value: Any?): Any? = jsonMapper.readValue(jsonMapper.writeValueAsString(value), Any::class.java)

    override fun <T : Any> fromTree(tree: Any?, type: Class<T>): T = jsonMapper.convertValue(tree, type)
}
