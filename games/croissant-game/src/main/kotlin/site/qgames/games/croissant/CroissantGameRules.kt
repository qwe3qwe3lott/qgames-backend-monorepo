package site.qgames.games.croissant

import java.util.Random
import java.util.UUID
import site.qgames.common.docpatch.PathSegment
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.GameStart
import site.qgames.games.core.MoveOption
import site.qgames.games.core.Result
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.ResultState
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEvents
import site.qgames.games.core.SessionState
import site.qgames.games.core.SettingsViolation
import site.qgames.games.core.Stages
import site.qgames.games.core.TimeBonusType
import site.qgames.games.core.TimeControlRules
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.core.Turns
import site.qgames.games.core.TurnsState

/**
 * «Круассан» — сеть пекарен в Долине Круассанов. Одна стадия `play`, ход из двух шагов: выложить тайл, затем по желанию
 * поставить кондитера на участок нового тайла. Часы игрока покрывают весь ход.
 *
 * Завершённые пекарни, тропы и сыроварни приносят «Золотые крошки» сразу, луга — только в итоговом подсчёте, когда
 * колода кончилась. Порядок колоды выводится из seed партии и в состояние не попадает: клиент видит только счётчик.
 */
class CroissantGameRules :
    GameRules<CroissantGameState, CroissantMove, CroissantSimpleSettings, CroissantAdvancedSettings> {

    override val code = CODE
    override val minPlayers = 2
    override val maxPlayers = 5

    override fun gameStateClass(stage: String) = CroissantGameState::class.java

    override fun moveClass(stage: String) = CroissantMove::class.java

    override val simpleClass = CroissantSimpleSettings::class.java
    override val advancedClass = CroissantAdvancedSettings::class.java

    override val timeControl = TimeControlRules(optional = true, baseSeconds = 300..3600, bonusSeconds = 0..60)

    override val defaultSimple = CroissantSimpleSettings(CroissantTime.NONE)

    override val settingsLimits = CroissantSettingsLimits(CroissantConfectionersRange, CroissantTimePresets)

    override fun playerClass(stage: String) = CroissantPlayer::class.java

    override fun validateSimple(settings: CroissantSimpleSettings): List<SettingsViolation> = emptyList()

    override fun validateAdvanced(settings: CroissantAdvancedSettings): List<SettingsViolation> =
        if (settings.confectioners in CroissantConfectionersRange) {
            emptyList()
        } else {
            listOf(
                SettingsViolation(
                    "confectioners",
                    "must be between ${CroissantConfectionersRange.first} and ${CroissantConfectionersRange.last}",
                ),
            )
        }

    /** Simple — только пресет времени; кондитеров в simple-режиме игра берёт по умолчанию. */
    override fun toAdvanced(settings: CroissantSimpleSettings): CroissantAdvancedSettings {
        val preset = CroissantTimePresets.firstOrNull { it.time == settings.time }
        return CroissantAdvancedSettings(
            confectioners = CroissantDefaults.confectioners,
            timeControl = TimeControlSettings(
                enabled = settings.time != CroissantTime.NONE,
                baseSeconds = preset?.baseSeconds ?: CroissantDefaults.timeControl.baseSeconds,
                bonusType = TimeBonusType.INCREMENT,
                bonusSeconds = preset?.bonusSeconds ?: 0,
            ),
        )
    }

    override fun clock(settings: CroissantAdvancedSettings): TimeControlSettings = settings.timeControl

    override fun start(players: List<UUID>, seed: Long, settings: CroissantAdvancedSettings): GameStart<CroissantGameState> {
        val startTile = CroissantPlacedTile(id = CELL_ORIGIN, x = 0, y = 0, tile = CroissantCatalog.START_TILE, rotation = 0)
        val board = listOf(startTile)
        val layout = CroissantLayout(board, CroissantCatalog.TYPES)
        val spots = CroissantNetwork.rebuild(layout, board, emptyList())
        val deck = CroissantCatalog.deck(seed)
        val drawn = draw(board, CroissantCatalog.TYPES, deck, from = 0)
        val first = players[Math.floorMod(Random(seed).nextInt(), players.size)]
        return GameStart(
            stage = Stages.PLAY,
            game = CroissantGameState(
                board = board,
                spots = spots,
                tiles = CroissantCatalog.TYPES,
                pending = null,
                currentTile = drawn.tile,
                deckPos = drawn.pos,
                deckSize = deck.size,
            ),
            turns = Turns.initial(players, rounds = null, first = first),
            players = players.associateWith { CroissantPlayer(crumbs = 0, confectioners = settings.confectioners) },
        )
    }

    override fun decide(
        state: SessionState<CroissantGameState>,
        settings: CroissantAdvancedSettings,
        seed: Long,
        playerId: UUID,
        move: CroissantMove,
    ): Decision {
        val current = state.turns.currentPlayerId ?: return rejected(ERR_FINISHED)
        if (current != playerId) return rejected(ERR_NOT_YOUR_TURN)
        val turn = turn(state, seed)
        val error = if (state.game.pending == null) turn.place(playerId, move) else turn.assign(playerId, move)
        return if (error == null) Decision.Accepted(turn.events) else rejected(error)
    }

    /**
     * Время игрока вышло: тайл кладётся в случайный законный вариант по seed, кондитер не ставится — партия не стоит.
     * На шаге кондитера ход просто завершается.
     */
    override fun onTimeout(
        state: SessionState<CroissantGameState>,
        settings: CroissantAdvancedSettings,
        seed: Long,
    ): List<SessionEvent> {
        val current = state.turns.currentPlayerId ?: return emptyList()
        val turn = turn(state, seed)
        if (state.game.pending != null) {
            turn.events += SessionEvents.info(CONFECTIONER_SKIPPED, mapOf("playerId" to current, "timeout" to true))
            turn.advance(current)
            return turn.events
        }
        val tile = state.game.currentTile ?: return emptyList()
        val placements = CroissantLayout(state.game.board, state.game.tiles).placements(tile)
        if (placements.isEmpty()) return emptyList()
        val random = Random(seed * ODD + state.game.board.size)
        val placement = placements[random.nextInt(placements.size)]
        val rotation = placement.rotations[random.nextInt(placement.rotations.size)]
        turn.events += SessionEvents.info(
            MOVE_TIMED_OUT,
            mapOf("playerId" to current, "x" to placement.x, "y" to placement.y, "rotation" to rotation),
        )
        turn.place(current, CroissantMove(PLACE_TILE, CroissantMoveData(placement.x, placement.y, rotation)))
        if (turn.game.pending != null) {
            turn.events += SessionEvents.info(CONFECTIONER_SKIPPED, mapOf("playerId" to current, "timeout" to true))
            turn.advance(current)
        }
        return turn.events
    }

    override fun moveOptions(state: SessionState<CroissantGameState>, playerId: UUID): List<MoveOption> {
        if (state.turns.currentPlayerId != playerId) return emptyList()
        val me = state.players.firstOrNull { it.id == playerId }?.game as? CroissantPlayer ?: return emptyList()
        val pending = state.game.pending
        if (pending != null) {
            return if (me.confectioners > 0 && pending.spots.isNotEmpty()) {
                listOf(
                    MoveOption(PLACE_CONFECTIONER, mapOf(SPOTS to pending.spots)),
                    MoveOption(SKIP_CONFECTIONER),
                )
            } else {
                listOf(MoveOption(SKIP_CONFECTIONER))
            }
        }
        val tile = state.game.currentTile ?: return emptyList()
        val placements = CroissantLayout(state.game.board, state.game.tiles).placements(tile)
        if (placements.isEmpty()) return emptyList()
        val data = placements.map { mapOf("x" to it.x, "y" to it.y, "rotations" to it.rotations) }
        return listOf(MoveOption(PLACE_TILE, mapOf(CURRENT_TILE to tile, "placements" to data)))
    }

    private fun turn(state: SessionState<CroissantGameState>, seed: Long) = Turn(
        game = state.game,
        turns = state.turns,
        players = state.players.map { it.id },
        fields = state.players.associate { it.id to (it.game as CroissantPlayer) }.toMutableMap(),
        seed = seed,
    )

    private fun rejected(code: String) = Decision.Rejected(code, MESSAGE[code] ?: code)

    /** Первый тайл партии из seed: тайлы, которые некуда положить, пропускаются. */
    private fun draw(
        board: List<CroissantPlacedTile>,
        tiles: List<CroissantTileType>,
        deck: List<String>,
        from: Int,
    ): Drawn {
        var pos = from
        val layout = CroissantLayout(board, tiles)
        while (pos < deck.size) {
            val tile = deck[pos]
            pos += 1
            if (layout.placements(tile).isNotEmpty()) return Drawn(tile, pos)
        }
        return Drawn(null, pos)
    }

    private data class Drawn(val tile: String?, val pos: Int)

    /**
     * Один ход: состояние игры и очерёдность, очки и кондитеры игроков, накопленные события. Журнал меняется только
     * этими событиями, поэтому свои изменения `Turn` держит рядом с ними.
     */
    private inner class Turn(
        game: CroissantGameState,
        turns: TurnsState,
        private val players: List<UUID>,
        private val fields: MutableMap<UUID, CroissantPlayer>,
        seed: Long,
    ) {
        var game = game
            private set
        var turns = turns
            private set
        val events = mutableListOf<SessionEvent>()

        private val deck = CroissantCatalog.deck(seed)

        /** Выложить тайл; [ERR_*] — отказ, null — ход принят (ждёт кондитера или уже передал очередь). */
        fun place(playerId: UUID, move: CroissantMove): String? {
            if (move.type != PLACE_TILE) return ERR_UNKNOWN_MOVE
            val tileCode = game.currentTile ?: return ERR_FINISHED
            val data = move.data ?: return ERR_ILLEGAL_MOVE
            val x = data.x ?: return ERR_ILLEGAL_MOVE
            val y = data.y ?: return ERR_ILLEGAL_MOVE
            val rotation = data.rotation ?: 0
            val layout = CroissantLayout(game.board, game.tiles)
            if (!layout.accepts(tileCode, x, y, rotation)) return ERR_ILLEGAL_PLACEMENT

            val placed = CroissantPlacedTile(layout.cellId(x, y), x, y, tileCode, rotation)
            val board = game.board + placed
            val rebuilt = CroissantNetwork.rebuild(CroissantLayout(board, game.tiles), board, game.spots)
            val before = game.spots.associateBy { it.id }
            val completedNow = rebuilt.filter { it.complete && before[it.id]?.complete != true }

            events += SessionEvents.put(TILE_PLACED, boardPath(placed.id), placed)

            val crumbs = mutableMapOf<UUID, Int>()
            val returned = mutableMapOf<UUID, Int>()
            val settled = rebuilt.map { spot ->
                if (completedNow.none { it.id == spot.id }) return@map spot
                val points = CroissantScoring.completed(spot)
                if (points > 0) {
                    CroissantScoring.winners(spot).forEach { winner -> crumbs.merge(winner, points, Int::plus) }
                }
                spot.confectioners.forEach { returned.merge(it.playerId, 1, Int::plus) }
                spot.copy(confectioners = emptyList())
            }
            events += spotEvents(game.spots, settled)
            completedNow.forEach { spot ->
                events += SessionEvents.info(
                    SPOT_COMPLETED,
                    mapOf("spotId" to spot.id, "type" to spot.type.name, "crumbs" to CroissantScoring.completed(spot)),
                )
            }
            award(crumbs, returned)
            game = game.copy(board = board, spots = settled)

            val free = settled.filter { spot ->
                spot.members.any { it.tileId == placed.id } && !spot.complete && spot.confectioners.isEmpty()
            }
            val me = fields.getValue(playerId)
            if (free.isEmpty() || me.confectioners == 0) {
                advance(playerId)
                return null
            }
            val pending = CroissantPending(placed.id, free.map { it.id }, completedNow.map { it.id })
            events += SessionEvents.put(TILE_PENDING, statePath(PENDING), pending)
            game = game.copy(pending = pending)
            return null
        }

        /** Поставить кондитера или пропустить шаг; дальше очередь переходит. */
        fun assign(playerId: UUID, move: CroissantMove): String? {
            val pending = game.pending ?: return ERR_UNKNOWN_MOVE
            if (move.type == SKIP_CONFECTIONER) {
                events += SessionEvents.info(CONFECTIONER_SKIPPED, mapOf("playerId" to playerId))
                advance(playerId)
                return null
            }
            if (move.type != PLACE_CONFECTIONER) return ERR_UNKNOWN_MOVE
            val spotId = move.data?.spotId ?: return ERR_ILLEGAL_MOVE
            if (spotId !in pending.spots) return ERR_ILLEGAL_CONFECTIONER
            val spot = game.spots.firstOrNull { it.id == spotId } ?: return ERR_ILLEGAL_CONFECTIONER
            val me = fields.getValue(playerId)
            if (me.confectioners <= 0) return ERR_NO_CONFECTIONERS
            if (spot.confectioners.isNotEmpty()) return ERR_SPOT_TAKEN
            val updated = spot.copy(confectioners = spot.confectioners + CroissantConfectioner(playerId, pending.tileId))
            events += SessionEvents.put(CONFECTIONER_PLACED, spotPath(spotId), updated)
            events += SessionEvents.patch(
                CONFECTIONER_PLACED,
                playerPath(playerId),
                mapOf(CONFECTIONERS to me.confectioners - 1),
            )
            fields[playerId] = me.copy(confectioners = me.confectioners - 1)
            game = game.copy(spots = game.spots.map { if (it.id == spotId) updated else it })
            advance(playerId)
            return null
        }

        /** Ход завершён: снять ожидание, взять следующий тайл и передать очередь; колода кончилась — итоговый подсчёт. */
        fun advance(playerId: UUID) {
            if (game.pending != null) events += SessionEvents.delete(TILE_PENDING_CLEARED, statePath(PENDING))
            game = game.copy(pending = null)
            var pos = game.deckPos
            var next: String? = null
            while (pos < deck.size) {
                val candidate = deck[pos]
                pos += 1
                if (CroissantLayout(game.board, game.tiles).placements(candidate).isNotEmpty()) {
                    next = candidate
                    break
                }
                events += SessionEvents.info(TILE_DISCARDED, mapOf("tile" to candidate, "playerId" to playerId))
            }
            if (next == null) {
                events += SessionEvents.patch(TILE_EXHAUSTED, statePath(), mapOf(CURRENT_TILE to null, DECK_POS to pos))
                game = game.copy(currentTile = null, deckPos = pos)
                finish()
                return
            }
            events += SessionEvents.patch(TILE_DRAWN, statePath(), mapOf(CURRENT_TILE to next, DECK_POS to pos))
            val moved = Turns.next(turns, players)
            turns = moved
            events += Turns.changed(moved)
            game = game.copy(currentTile = next, deckPos = pos)
        }

        /** Итоговый подсчёт: незавершённые пекарни, тропы, сыроварни и луга; победители — по большинству кондитеров. */
        fun finish() {
            val layout = CroissantLayout(game.board, game.tiles)
            val gained = mutableMapOf<UUID, Int>()
            game.spots.filter { !it.complete }.forEach { spot ->
                val winners = CroissantScoring.winners(spot)
                if (winners.isEmpty()) return@forEach
                val points = CroissantScoring.final(spot, layout, game.spots)
                if (points > 0) winners.forEach { gained.merge(it, points, Int::plus) }
            }
            award(gained, emptyMap())
            val totals = fields.mapValues { it.value.crumbs }
            events += SessionEvents.info(GAME_SCORED, mapOf("crumbs" to totals.mapKeys { it.key.toString() }))
            val best = totals.values.maxOrNull() ?: 0
            val winners = totals.filterValues { it == best }.keys.sortedBy { it.toString() }
            val result = if (winners.size == 1) {
                ResultState(ResultOutcome.WIN, winners, RESULT_SCORED)
            } else {
                ResultState(ResultOutcome.DRAW, emptyList(), RESULT_SCORED)
            }
            events += Result.set(result)
            events += SessionEvents.finished()
        }

        private fun award(crumbs: Map<UUID, Int>, returned: Map<UUID, Int>) {
            crumbs.forEach { (id, points) ->
                val current = fields.getValue(id)
                val total = current.crumbs + points
                fields[id] = current.copy(crumbs = total)
                events += SessionEvents.patch(CRUMBS_CHANGED, playerPath(id), mapOf(CRUMBS to total))
            }
            returned.forEach { (id, count) ->
                val current = fields.getValue(id)
                val total = current.confectioners + count
                fields[id] = current.copy(confectioners = total)
                events += SessionEvents.patch(CONFECTIONERS_RETURNED, playerPath(id), mapOf(CONFECTIONERS to total))
            }
        }
    }

    private fun spotEvents(previous: List<CroissantSpot>, next: List<CroissantSpot>): List<SessionEvent> {
        val previousById = previous.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        val removed = previous.filter { it.id !in nextById }.map { SessionEvents.delete(SPOT_REMOVED, spotPath(it.id)) }
        val changed = next.filter { previousById[it.id] != it }.map { SessionEvents.put(SPOT_UPDATED, spotPath(it.id), it) }
        return removed + changed
    }

    private fun statePath(vararg keys: Any): List<PathSegment> = SessionEvents.path(SessionEvents.STATE, *keys)

    private fun spotPath(id: String): List<PathSegment> = SessionEvents.path(SessionEvents.STATE, SPOTS, SessionEvents.id(id))

    private fun boardPath(id: String): List<PathSegment> = SessionEvents.path(SessionEvents.STATE, BOARD, SessionEvents.id(id))

    private fun playerPath(id: UUID): List<PathSegment> = SessionEvents.path(SessionEvents.STATE, PLAYERS, SessionEvents.id(id))

    companion object {
        const val CODE = "croissant"

        /** Стартовая клетка партии. */
        const val CELL_ORIGIN = "0:0"

        const val PLACE_TILE = "PLACE_TILE"
        const val PLACE_CONFECTIONER = "PLACE_CONFECTIONER"
        const val SKIP_CONFECTIONER = "SKIP_CONFECTIONER"

        const val TILE_PLACED = "tile-placed"
        const val TILE_PENDING = "tile-pending"
        const val TILE_PENDING_CLEARED = "tile-pending-cleared"
        const val TILE_DRAWN = "tile-drawn"
        const val TILE_EXHAUSTED = "tile-exhausted"
        const val TILE_DISCARDED = "tile-discarded"
        const val SPOT_UPDATED = "spot-updated"
        const val SPOT_REMOVED = "spot-removed"
        const val SPOT_COMPLETED = "spot-completed"
        const val CONFECTIONER_PLACED = "confectioner-placed"
        const val CONFECTIONER_SKIPPED = "confectioner-skipped"
        const val CONFECTIONERS_RETURNED = "confectioners-returned"
        const val CRUMBS_CHANGED = "crumbs-changed"
        const val GAME_SCORED = "game-scored"
        const val MOVE_TIMED_OUT = "move-timed-out"

        const val RESULT_SCORED = "SCORED"

        const val ERR_FINISHED = "GAME_FINISHED"
        const val ERR_NOT_YOUR_TURN = "NOT_YOUR_TURN"
        const val ERR_UNKNOWN_MOVE = "UNKNOWN_MOVE"
        const val ERR_ILLEGAL_MOVE = "ILLEGAL_MOVE"
        const val ERR_ILLEGAL_PLACEMENT = "ILLEGAL_PLACEMENT"
        const val ERR_ILLEGAL_CONFECTIONER = "ILLEGAL_CONFECTIONER"
        const val ERR_NO_CONFECTIONERS = "NO_CONFECTIONERS"
        const val ERR_SPOT_TAKEN = "SPOT_TAKEN"

        private val MESSAGE = mapOf(
            ERR_FINISHED to "The game is already finished",
            ERR_NOT_YOUR_TURN to "It is not your turn",
            ERR_UNKNOWN_MOVE to "Unknown move",
            ERR_ILLEGAL_MOVE to "The move lacks required fields",
            ERR_ILLEGAL_PLACEMENT to "The tile does not fit here",
            ERR_ILLEGAL_CONFECTIONER to "No confectioner can be placed on this spot",
            ERR_NO_CONFECTIONERS to "You have no confectioners left",
            ERR_SPOT_TAKEN to "The spot already has a confectioner",
        )

        private const val PLAYERS = "players"
        private const val BOARD = "board"
        private const val SPOTS = "spots"
        private const val PENDING = "pending"
        private const val CURRENT_TILE = "currentTile"
        private const val DECK_POS = "deckPos"
        private const val CRUMBS = "crumbs"
        private const val CONFECTIONERS = "confectioners"

        /** Нечётный множитель для вывода случайности таймаута из seed. */
        private const val ODD = 31L
    }
}
