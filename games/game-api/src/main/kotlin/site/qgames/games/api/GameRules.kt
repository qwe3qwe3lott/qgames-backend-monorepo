package site.qgames.games.api

import java.time.Duration
import java.util.UUID

/**
 * Правила одной игры. Реализация — детерминированные чистые функции: одинаковые состояние и ход дают одинаковый
 * результат на любом инстансе и при переигрывании журнала ходов. Случайность берётся только из seed.
 *
 * Состояние, ход и настройки сериализуются сервисом game в JSON (jsonb), поэтому [S], [M] и [C] — простые data-классы
 * без вычисляемых свойств. При несовместимом изменении формата состояния увеличивается [stateSchemaVersion].
 */
interface GameRules<S : Any, M : Any, C : Any> {

    /**
     * Код игры, например `nothing`: совпадает с кодом в каталоге игр сервиса game (таблица game, enum GameCode)
     * и в путях API (`/api/games/{gameCode}`).
     */
    val code: String
    val minPlayers: Int
    val maxPlayers: Int
    val stateClass: Class<S>
    val moveClass: Class<M>
    val settingsClass: Class<C>
    val stateSchemaVersion: Int

    /** Какие режимы времени поддерживает игра, их границы и режим по умолчанию. */
    val timeControl: TimeControlRules

    /**
     * Сколько игрок идущей партии может отсутствовать после выхода из комнаты, прежде чем перестанет считаться
     * присутствующим (место в партии при этом сохраняется). На паузе партии отсчёт заморожен.
     */
    val reconnectGrace: Duration

    /** Настройки игры по умолчанию: ими заполняется форма создания комнаты. */
    val defaultSettings: C

    /** Границы настроек игры для формы. Формат задаёт игра, сервис game переводит его в DTO контракта. */
    val settingsLimits: Any

    /** Нарушения в настройках игры; пустой список — настройки подходят. Поле — путь внутри [C], например `rounds`. */
    fun validateSettings(settings: C): List<SettingsViolation>

    /** Игроки перечислены в порядке мест. Настройки уже проверены [validateSettings]. */
    fun initialState(players: List<UUID>, seed: Long, settings: C): S

    fun applyMove(state: S, player: UUID, move: M): MoveResult<S>

    fun isFinished(state: S): Boolean

    /**
     * Чей сейчас ход: на него идёт таймер хода, и от его имени в журнал пишется истечение времени.
     * null — партия закончена или ход ничей.
     */
    fun playerToMove(state: S): UUID?

    /** Время хода [playerToMove] истекло. Что при этом происходит, решает игра (например, автоматический пропуск хода). */
    fun onMoveTimeout(state: S): S

    /** То, что видит конкретный участник (игрок или наблюдатель). Здесь скрывается чужая закрытая информация. */
    fun view(state: S, viewer: UUID): Any
}

sealed interface MoveResult<out S> {

    data class Applied<S>(val state: S) : MoveResult<S>

    /** [code] — машиночитаемая причина, отдаётся клиенту. */
    data class Rejected(val code: String, val message: String) : MoveResult<Nothing>
}

/** Нарушение в настройках игры: [field] — путь к полю внутри настроек игры, [message] — описание для клиента. */
data class SettingsViolation(val field: String, val message: String)
