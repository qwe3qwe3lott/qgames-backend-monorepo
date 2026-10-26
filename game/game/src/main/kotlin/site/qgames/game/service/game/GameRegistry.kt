package site.qgames.game.service.game

import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.games.core.GameRules

/**
 * Правила подключённых игр по коду. Модуль правил есть не у каждой игры каталога: неопубликованной игре (NOT_PUBLISHED)
 * он не обязателен. Соответствие каталога и модулей проверяет GameCatalogVerifier при старте.
 */
@Component
class GameRegistry(rules: List<GameRules<*, *, *>>) {

    private val byCode: Map<GameCode, GameRules<Any, Any, Any>> = rules.associate {
        @Suppress("UNCHECKED_CAST")
        val typed = it as GameRules<Any, Any, Any>
        (GameCode.fromCode(it.code) ?: error("Rules ${it::class.qualifiedName} have unknown game code '${it.code}'")) to typed
    }

    /**
     * Правила игры, у которой они обязаны быть: комнаты и партии бывают только у игр с правилами (игры без
     * правил наружу не выдаются).
     */
    fun rules(code: GameCode): GameRules<Any, Any, Any> = rulesOrNull(code) ?: error("No rules registered for game $code")

    /** Правила игры; null — модуля правил нет (неопубликованная игра). */
    fun rulesOrNull(code: GameCode): GameRules<Any, Any, Any>? = byCode[code]
}
