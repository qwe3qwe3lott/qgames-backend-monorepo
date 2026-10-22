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
    CHECKERS("checkers"),
    ;

    companion object {
        fun fromCode(code: String): GameCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Статус публикации игры: что видно в каталоге и можно ли работать с игрой. PUBLISHED и HIDDEN допустимы только у игры
 * с модулем правил (проверяет GameCatalogVerifier).
 */
enum class GamePublishStatus(
    /**
     * Игра видна наружу: детальная ручка её отдаёт, с её комнатами и партиями можно работать (в том числе создавать
     * комнаты и начинать партии). Иначе — GAME_NOT_FOUND.
     */
    val exposed: Boolean,
    /** Игра есть в каталоге. */
    val listed: Boolean,
) {
    PUBLISHED(exposed = true, listed = true),

    /** Скрыта из каталога, но доступна по прямой ссылке. */
    HIDDEN(exposed = true, listed = false),

    /**
     * Не опубликована: в разработке или отключена. Игры как будто нет (GAME_NOT_FOUND), NotPublishedGameCleanupJob
     * отменяет её партии и удаляет комнаты. Единственный статус, при котором у игры может не быть модуля правил.
     */
    NOT_PUBLISHED(exposed = false, listed = false),
}
