package site.qgames.game.model.game

import java.util.UUID

/** Игра из каталога (таблица game). */
data class GameModel(
    val id: UUID,
    val code: GameCode,
    val title: String,
    val description: String,
    /** Порядок в каталоге: по возрастанию. */
    val sortOrder: Int,
    val publishStatus: GamePublishStatus,
)

/**
 * Код игры. [code] — строчный код в БД, в путях API и в GameRules.code модуля игры. Набор значений совпадает с
 * таблицей game: это проверяется при старте (GameCatalogVerifier).
 */
enum class GameCode(val code: String) {
    NOTHING("nothing"),
    CARCASSONNE("carcassonne"),
    ;

    companion object {
        fun fromCode(code: String): GameCode? = entries.firstOrNull { it.code == code }
    }
}

/** Статус публикации игры: что видно в каталоге и что разрешено делать с игрой. */
enum class GamePublishStatus(
    /** Игра видна наружу: детальная ручка её отдаёт, с её комнатами можно работать. Иначе — GAME_NOT_FOUND. */
    val exposed: Boolean,
    /** Игра есть в каталоге. */
    val listed: Boolean,
    /** Можно создавать комнаты и начинать партии. */
    val playable: Boolean,
) {
    PUBLISHED(exposed = true, listed = true, playable = true),

    /** Скрыта из каталога, но доступна по прямой ссылке. */
    HIDDEN(exposed = true, listed = false, playable = true),

    /** Временно недоступна: новые комнаты и партии запрещены (GAME_UNAVAILABLE), идущие партии продолжаются. */
    DISABLED(exposed = true, listed = true, playable = false),

    /**
     * Не опубликована (в разработке): игры как будто нет (GAME_NOT_FOUND), её комнаты при этом не удаляются.
     * Единственный статус, при котором у игры может не быть модуля правил.
     */
    NOT_PUBLISHED(exposed = false, listed = false, playable = false),

    /**
     * Принудительно отключена: игры как будто нет (GAME_NOT_FOUND), ForceDisabledGameCleanupJob отменяет её партии
     * и удаляет комнаты.
     */
    FORCE_DISABLED(exposed = false, listed = false, playable = false),
}
