package site.qgames.games.checkers

import java.util.UUID
import kotlin.random.Random
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.GameStart
import site.qgames.games.core.MoveOption
import site.qgames.games.core.PlayerState
import site.qgames.games.core.Result
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEvents
import site.qgames.games.core.SessionState
import site.qgames.games.core.SettingsViolation
import site.qgames.games.core.TimeControlRules
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.core.Turns

/**
 * Шашки. Simple-настройки — вариант правил и пресет времени ([CheckersSimpleSettings]), advanced — вариант, часы и флаги
 * правил хода ([CheckersAdvancedSettings]). Ходы генерируются и проверяются только по итоговым флагам
 * [CheckersAdvancedRules] ([CheckersBoard]), они же уходят клиенту в `meta.advancedRules`.
 *
 * Core: варианты ходов (MOVE и RESIGN у того, чей ход, и ACCEPT_DRAW, если соперник предложил ничью; RESIGN у
 * соперника; цвет игрока — поле игры в `players[]`), очерёдность (ход переходит к сопернику, раундов нет), часы
 * (время вышло — поражение), итог. Скрытой информации нет, маски не нужны.
 *
 * Ход MOVE — полный путь шашки `{path: [[x, y], …], offerDraw?}` с начальной клеткой; события: `piece-moved`
 * (`PATCH ["state", "pieces", {id}] {square}`), `piece-captured` (`DELETE` на каждую сбитую), `piece-crowned`
 * (`PATCH {kind: KING}`), `last-move-changed` (`PUT ["state", "lastMove"]`), `counters-changed`
 * (`PATCH ["state"] {quietMoves, materialMoves}`), `position-recorded` (`PUT ["state", "positionHistory"]`),
 * `draw-offer-changed` (`PATCH ["state"] {drawOfferedBy}`), затем `turn-changed` или итог (`result-set`) и
 * `session-finished`. Ничья по соглашению: `offerDraw` предлагает её вместе с ходом, соперник в свой ход принимает
 * (ACCEPT_DRAW: `draw-accepted` `INFO {playerId}`) или отказывается своим ходом (предложение снимается). Сдача RESIGN — в
 * любой момент, у любого игрока: `player-resigned` (`INFO {playerId}`). Время вышло — `move-timed-out`
 * (`INFO {playerId}`) и поражение.
 *
 * Окончание: у соперника нет шашек (`NO_PIECES`) или ходов (`NO_MOVES`), сдача (`RESIGN`), время (`TIMEOUT`) — победа;
 * ничья — по соглашению (`DRAW_AGREED`), троекратное повторение позиции (`REPETITION`), правило окончаний варианта
 * (`ENDGAME_RULE`, [CheckersDraws]), [drawQuietMoves] варианта ходов подряд только дамками без взятий (`DRAW_RULE`).
 */
class CheckersGameRules : GameRules<CheckersGameState, CheckersMove, CheckersSimpleSettings, CheckersAdvancedSettings> {

    override val code = CODE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val gameStateClass = CheckersGameState::class.java
    override val moveClass = CheckersMove::class.java
    override val simpleClass = CheckersSimpleSettings::class.java
    override val advancedClass = CheckersAdvancedSettings::class.java
    override val playerClass = CheckersPlayer::class.java

    override val timeControl = TimeControlRules(optional = true, baseSeconds = 60..10800, incrementSeconds = 0..60)

    override val defaultSimple = CheckersSimpleSettings(CheckersVariant.RUSSIAN, CheckersTime.RAPID)

    override val settingsLimits = CheckersSettingsLimits(VARIANTS, TIME_PRESETS)

    override fun validateSimple(settings: CheckersSimpleSettings): List<SettingsViolation> = validateVariant(settings.variant)

    override fun validateAdvanced(settings: CheckersAdvancedSettings): List<SettingsViolation> = validateVariant(settings.variant)

    /**
     * Флаги — правила варианта по умолчанию, часы — пресет варианта. Без таймера часы заполняются пресетом RAPID — на
     * случай включения в advanced.
     */
    override fun toAdvanced(settings: CheckersSimpleSettings): CheckersAdvancedSettings {
        val rules = CheckersAdvancedRules.of(settings.variant)
        val presetTime = if (settings.time == CheckersTime.NONE) CheckersTime.RAPID else settings.time
        val preset = TIME_PRESETS.single { it.variant == settings.variant && it.time == presetTime }
        return CheckersAdvancedSettings(
            variant = settings.variant,
            timeControl = TimeControlSettings(settings.time != CheckersTime.NONE, preset.baseSeconds, preset.incrementSeconds),
            menMoveBackward = rules.menMoveBackward,
            menCaptureBackward = rules.menCaptureBackward,
            flyingKings = rules.flyingKings,
            captureRequired = rules.captureRequired,
            maxCaptureRequired = rules.maxCaptureRequired,
            promoteMidCapture = rules.promoteMidCapture,
            removeCapturedAfterMove = rules.removeCapturedAfterMove,
        )
    }

    override fun clock(settings: CheckersAdvancedSettings) = settings.timeControl

    override fun advancedRules(settings: CheckersAdvancedSettings) = CheckersAdvancedRules.of(settings)

    /** Цвета — случайно по seed; расстановка — на тёмных клетках ближних к игроку рядов; первым ходит firstColor. */
    override fun start(players: List<UUID>, seed: Long, settings: CheckersAdvancedSettings): GameStart<CheckersGameState> {
        val moveRules = advancedRules(settings)
        val firstSeatColor = if (Random(seed).nextBoolean()) CheckersColor.WHITE else CheckersColor.BLACK
        val colors = mapOf(players[0] to firstSeatColor, players[1] to firstSeatColor.opponent())
        val first = colors.entries.single { it.value == moveRules.firstColor }.key
        val pieces = initialPieces(moveRules.boardSize)
        return GameStart(
            game = CheckersGameState(
                pieces = pieces,
                lastMove = null,
                quietMoves = 0,
                materialMoves = 0,
                positionHistory = listOf(CheckersDraws.positionKey(moveRules.firstColor, pieces)),
                drawOfferedBy = null,
            ),
            turns = Turns.initial(players, rounds = null, first = first),
            players = colors.mapValues { CheckersPlayer(it.value) },
        )
    }

    override fun decide(state: SessionState<CheckersGameState>, settings: CheckersAdvancedSettings, playerId: UUID, move: CheckersMove): Decision {
        val current = state.turns.currentPlayerId
        return when {
            current == null || state.result != null -> Decision.Rejected(GAME_FINISHED, "The game is already finished")
            move.type == RESIGN -> Decision.Accepted(
                listOf(SessionEvents.info(PLAYER_RESIGNED, mapOf(PLAYER_ID to playerId))) + win(opponentOf(state, playerId), REASON_RESIGN),
            )
            move.type != MOVE && move.type != ACCEPT_DRAW -> Decision.Rejected(UNKNOWN_MOVE, "Allowed moves are $MOVE, $ACCEPT_DRAW and $RESIGN")
            current != playerId -> Decision.Rejected(NOT_YOUR_TURN, "It is not your turn")
            move.type == ACCEPT_DRAW -> acceptDraw(state, playerId)
            else -> movePiece(state, settings, playerId, move.data)
        }
    }

    /** Принять ничью: только в свой ход и только предложенную соперником. */
    private fun acceptDraw(state: SessionState<CheckersGameState>, playerId: UUID): Decision {
        if (state.game.drawOfferedBy != opponentOf(state, playerId)) return illegal("There is no draw offer from the opponent")
        return Decision.Accepted(
            listOf(
                SessionEvents.info(DRAW_ACCEPTED, mapOf(PLAYER_ID to playerId)),
                Result.draw(REASON_DRAW_AGREED),
                SessionEvents.finished(),
            ),
        )
    }

    /** Время хода вышло — поражение того, чей ход. */
    override fun onTimeout(state: SessionState<CheckersGameState>): List<SessionEvent> {
        val current = state.turns.currentPlayerId ?: return emptyList()
        return listOf(SessionEvents.info(MOVE_TIMED_OUT, mapOf(PLAYER_ID to current))) + win(opponentOf(state, current), REASON_TIMEOUT)
    }

    override fun moveOptions(state: SessionState<CheckersGameState>, playerId: UUID): List<MoveOption> = when {
        state.result != null || state.turns.currentPlayerId == null -> emptyList()
        state.turns.currentPlayerId == playerId -> listOfNotNull(
            MoveOption(MOVE),
            MoveOption(ACCEPT_DRAW).takeIf { state.game.drawOfferedBy == opponentOf(state, playerId) },
            MoveOption(RESIGN),
        )
        else -> listOf(MoveOption(RESIGN))
    }

    private fun validateVariant(variant: CheckersVariant): List<SettingsViolation> =
        if (variant in VARIANTS) {
            emptyList()
        } else {
            listOf(SettingsViolation("variant", "must be one of ${VARIANTS.joinToString()}"))
        }

    private fun movePiece(state: SessionState<CheckersGameState>, settings: CheckersAdvancedSettings, playerId: UUID, data: CheckersMoveData?): Decision {
        val variant = settings.variant
        val moveRules = CheckersAdvancedRules.of(settings)
        val path = data?.path?.map { Square.of(it) ?: return illegal("Every square of the path must be [x, y]") }
            ?: return illegal("The path is required")
        val color = colorOf(state, playerId)
        val board = CheckersBoard(moveRules, state.game.pieces)
        val move = board.legalMoves(color).firstOrNull { it.path == path }
            ?: return illegal("The path is not a legal move: wrong squares, an unfinished capture or a required capture is missed")

        val pieces = board.after(move)
        val game = state.game
        val quietMoves = if (move.captured.isEmpty() && move.piece.kind == CheckersPieceKind.KING) game.quietMoves + 1 else 0
        val materialMoves = if (move.captured.isEmpty() && !move.crowned) game.materialMoves + 1 else 0
        val opponent = opponentOf(state, playerId)
        val opponentColor = color.opponent()
        // Взятие и ход простой необратимы: прежние позиции больше не повторятся.
        val key = CheckersDraws.positionKey(opponentColor, pieces)
        val irreversible = move.captured.isNotEmpty() || move.piece.kind == CheckersPieceKind.MAN
        val positionHistory = if (irreversible) listOf(key) else game.positionHistory + key
        // Своим ходом игрок отказывается от предложения соперника и может предложить ничью сам.
        val drawOfferedBy = if (data.offerDraw) playerId else null
        val events = buildList {
            add(SessionEvents.patch(PIECE_MOVED, piecePath(move.piece.id), mapOf(SQUARE to path.last().toList())))
            move.captured.forEach { add(SessionEvents.delete(PIECE_CAPTURED, piecePath(it.id))) }
            if (move.crowned) add(SessionEvents.patch(PIECE_CROWNED, piecePath(move.piece.id), mapOf(KIND to CheckersPieceKind.KING)))
            add(
                SessionEvents.put(
                    LAST_MOVE_CHANGED,
                    SessionEvents.path(SessionEvents.STATE, LAST_MOVE),
                    CheckersLastMove(move.piece.id, move.path.map(Square::toList), move.captured.map { it.square }),
                ),
            )
            if (quietMoves != game.quietMoves || materialMoves != game.materialMoves) {
                add(
                    SessionEvents.patch(
                        COUNTERS_CHANGED,
                        SessionEvents.path(SessionEvents.STATE),
                        mapOf(QUIET_MOVES to quietMoves, MATERIAL_MOVES to materialMoves),
                    ),
                )
            }
            add(SessionEvents.put(POSITION_RECORDED, SessionEvents.path(SessionEvents.STATE, POSITION_HISTORY), positionHistory))
            if (drawOfferedBy != game.drawOfferedBy) {
                add(SessionEvents.patch(DRAW_OFFER_CHANGED, SessionEvents.path(SessionEvents.STATE), mapOf(DRAW_OFFERED_BY to drawOfferedBy)))
            }
            when {
                pieces.none { it.color == opponentColor } -> addAll(win(playerId, REASON_NO_PIECES))
                CheckersBoard(moveRules, pieces).legalMoves(opponentColor).isEmpty() -> addAll(win(playerId, REASON_NO_MOVES))
                positionHistory.count { it == key } >= CheckersDraws.REPETITIONS -> addAll(draw(REASON_REPETITION))
                CheckersDraws.endgame(variant, pieces, color, materialMoves) -> addAll(draw(REASON_ENDGAME_RULE))
                quietMoves >= drawQuietMoves(variant) -> addAll(draw(REASON_DRAW_RULE))
                else -> add(Turns.changed(state.turns.copy(currentPlayerId = opponent)))
            }
        }
        return Decision.Accepted(events)
    }

    /** Итог-победа [winnerId] и конец партии. */
    private fun win(winnerId: UUID, reason: String) = listOf(Result.win(winnerId, reason), SessionEvents.finished())

    /** Итог-ничья и конец партии. */
    private fun draw(reason: String) = listOf(Result.draw(reason), SessionEvents.finished())

    private fun illegal(message: String) = Decision.Rejected(ILLEGAL_MOVE, message)

    private fun colorOf(state: SessionState<CheckersGameState>, playerId: UUID): CheckersColor = playerFields(player(state, playerId)).color

    private fun opponentOf(state: SessionState<CheckersGameState>, playerId: UUID): UUID = state.players.single { it.id != playerId }.id

    private fun player(state: SessionState<CheckersGameState>, playerId: UUID): PlayerState = state.players.single { it.id == playerId }

    // Поля игрока шашек платформа читает классом playerClass = CheckersPlayer, поэтому приведение безопасно.
    private fun playerFields(player: PlayerState): CheckersPlayer = player.game as CheckersPlayer

    private fun piecePath(id: String) = SessionEvents.path(SessionEvents.STATE, PIECES, SessionEvents.id(id))

    companion object {
        const val CODE = "checkers"

        const val MOVE = "MOVE"
        const val ACCEPT_DRAW = "ACCEPT_DRAW"
        const val RESIGN = "RESIGN"

        /** Варианты правил, доступные в настройках. */
        val VARIANTS = listOf(CheckersVariant.RUSSIAN, CheckersVariant.INTERNATIONAL, CheckersVariant.ENGLISH)

        /**
         * Официальные контроли времени (запас + добавка, секунды): русские — ФШР; международные — чемпионаты мира FMJD
         * (классика) и тай-брейки матча 2024 (быстрые, блиц); английские — неофициально (официального регламента
         * WCDF нет в открытом доступе). Двухэтапные контроли (90 мин на 45 ходов + …) часы не выражают.
         */
        val TIME_PRESETS = listOf(
            preset(CheckersVariant.RUSSIAN, CheckersTime.CLASSIC, 1800, 30),
            preset(CheckersVariant.RUSSIAN, CheckersTime.RAPID, 480, 4),
            preset(CheckersVariant.RUSSIAN, CheckersTime.BLITZ, 180, 2),
            preset(CheckersVariant.INTERNATIONAL, CheckersTime.CLASSIC, 4800, 60),
            preset(CheckersVariant.INTERNATIONAL, CheckersTime.RAPID, 1200, 10),
            preset(CheckersVariant.INTERNATIONAL, CheckersTime.BLITZ, 600, 5),
            preset(CheckersVariant.ENGLISH, CheckersTime.CLASSIC, 1800, 0),
            preset(CheckersVariant.ENGLISH, CheckersTime.RAPID, 720, 5),
            preset(CheckersVariant.ENGLISH, CheckersTime.BLITZ, 180, 3),
        )

        private fun preset(variant: CheckersVariant, time: CheckersTime, baseSeconds: Int, incrementSeconds: Int) =
            CheckersTimePreset(variant, time, baseSeconds, incrementSeconds)

        /**
         * Ничья: столько ходов подряд (обеих сторон в сумме) только дамками без взятий — по 15 у каждой в русских, по 25
         * в международных, по 40 в английских. Клиенту не нужен, поэтому не в [CheckersAdvancedRules].
         */
        fun drawQuietMoves(variant: CheckersVariant): Int = when (variant) {
            CheckersVariant.RUSSIAN -> 30
            CheckersVariant.INTERNATIONAL -> 50
            CheckersVariant.ENGLISH -> 80
        }

        const val PIECE_MOVED = "piece-moved"
        const val PIECE_CAPTURED = "piece-captured"
        const val PIECE_CROWNED = "piece-crowned"
        const val LAST_MOVE_CHANGED = "last-move-changed"
        const val COUNTERS_CHANGED = "counters-changed"
        const val POSITION_RECORDED = "position-recorded"
        const val DRAW_OFFER_CHANGED = "draw-offer-changed"
        const val DRAW_ACCEPTED = "draw-accepted"
        const val PLAYER_RESIGNED = "player-resigned"
        const val MOVE_TIMED_OUT = "move-timed-out"

        const val NOT_YOUR_TURN = "NOT_YOUR_TURN"
        const val UNKNOWN_MOVE = "UNKNOWN_MOVE"
        const val ILLEGAL_MOVE = "ILLEGAL_MOVE"
        const val GAME_FINISHED = "GAME_FINISHED"

        const val REASON_NO_PIECES = "NO_PIECES"
        const val REASON_NO_MOVES = "NO_MOVES"
        const val REASON_RESIGN = "RESIGN"
        const val REASON_TIMEOUT = "TIMEOUT"
        const val REASON_DRAW_RULE = "DRAW_RULE"
        const val REASON_DRAW_AGREED = "DRAW_AGREED"
        const val REASON_REPETITION = "REPETITION"
        const val REASON_ENDGAME_RULE = "ENDGAME_RULE"

        private const val PIECES = "pieces"
        private const val LAST_MOVE = "lastMove"
        private const val QUIET_MOVES = "quietMoves"
        private const val MATERIAL_MOVES = "materialMoves"
        private const val POSITION_HISTORY = "positionHistory"
        private const val DRAW_OFFERED_BY = "drawOfferedBy"
        private const val SQUARE = "square"
        private const val KIND = "kind"
        private const val PLAYER_ID = "playerId"

        /** Начальная расстановка: по `boardSize / 2 - 1` рядов тёмных клеток у каждой стороны. */
        fun initialPieces(boardSize: Int): List<CheckersPiece> {
            val rows = boardSize / 2 - 1
            val dark = { x: Int, y: Int -> (x + y) % 2 == 0 }
            val white = (1..rows).flatMap { y -> (1..boardSize).filter { dark(it, y) }.map { x -> Square(x, y) } }
            val black = (boardSize downTo boardSize - rows + 1).flatMap { y -> (boardSize downTo 1).filter { dark(it, y) }.map { x -> Square(x, y) } }
            return white.mapIndexed { i, s -> CheckersPiece("w${i + 1}", CheckersColor.WHITE, CheckersPieceKind.MAN, s.toList()) } +
                black.mapIndexed { i, s -> CheckersPiece("b${i + 1}", CheckersColor.BLACK, CheckersPieceKind.MAN, s.toList()) }
        }
    }
}
