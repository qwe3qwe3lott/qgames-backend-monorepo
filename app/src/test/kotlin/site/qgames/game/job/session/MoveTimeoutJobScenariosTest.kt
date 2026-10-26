package site.qgames.game.job.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.SessionStatus
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.games.nothing.NothingGameState
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import site.qgames.game.table.room.RoomTable
import site.qgames.game.table.session.RoomSessionTable
import site.qgames.game.table.session.RoomSessionEventTable

/**
 * Истечение времени хода: дедлайн-проекция сдвигается в прошлое через SQL, джоб вызывается напрямую (по расписанию он в
 * тестах выключен). В «игре в ничто» истечение времени — автоматический PASS игрока, чьё время вышло; события идут в
 * журнал тем же путём, что ход (пропуск — INFO player-passed, счётчик ходов не меняется).
 */
class MoveTimeoutJobScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    @Test
    fun `expired move is passed automatically and the next player gets a new deadline`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        expireMove(roomId)

        val before = Instant.now()
        val processed = moveTimeoutJob.run()
        val after = Instant.now()

        assertThat(processed).isEqualTo(1)
        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.IN_GAME)
        val state = fullState(roomId).state
        assertThat(state.turns.currentPlayerId).isEqualTo(BOB)
        assertThat(state.timer!!.deadline).isBetween(before.plusSeconds(30).minusMillis(1), after.plusSeconds(30))
        assertThat(journal(roomId).map { it.second })
            .containsExactly("session-started", "player-passed", "turn-changed", "move-options-changed", "move-options-changed", "clock-switched")
        // Пропуск по времени — INFO: в журнале, но счётчик ходов игры не меняется.
        val passed = db {
            RoomSessionEventTable.selectAll().where { RoomSessionEventTable.code eq "player-passed" }.single()
                .let { it[RoomSessionEventTable.method] to it[RoomSessionEventTable.data] }
        }
        assertThat(passed.first).isEqualTo("INFO")
        assertThat(state.game).isEqualTo(NothingGameState(moves = 0))
        assertThat(jsonMapper.readTree(passed.second)).isEqualTo(
            jsonMapper.readTree("""{"playerId": "$ALICE", "timeout": true}"""),
        )

        // Следующий ход — уже обычный ход Боба.
        pass(BOB, roomId)
    }

    @Test
    fun `timeout of the last move ends the game and frees the room`() {
        val roomId = roomWith(ALICE, started = true, settings = perMove(seconds = 10, rounds = 1))
        expireMove(roomId)

        assertThat(moveTimeoutJob.run()).isEqualTo(1)

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.session!!.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(journal(roomId).last().second).isEqualTo("session-finished")
        assertThat(count(RoomSessionTable) { RoomSessionTable.moveDeadline.isNull() and RoomSessionTable.finishedAt.isNotNull() }).isEqualTo(1)
        assertThat(count(RoomTable) { RoomTable.currentSessionId.isNull() and (RoomTable.status eq "WAITING") }).isEqualTo(1)
    }

    @Test
    fun `games without an expired deadline are left alone`() {
        val withoutTimer = roomWith(ALICE, started = true)
        val notExpired = roomWith(BOB, started = true, settings = perMove(seconds = 600))
        val cancelled = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        cancel(ALICE, cancelled)
        db { RoomSessionTable.update({ RoomSessionTable.roomId eq cancelled }) { it[moveDeadline] = Instant.now().minusSeconds(5) } }

        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(count(RoomSessionEventTable) { RoomSessionEventTable.code eq "player-passed" }).isZero()
        assertThat(fullState(withoutTimer).state.timer).isNull()
        assertThat(journal(notExpired)).hasSize(1)
    }

    @Test
    fun `timeout is not applied twice`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        expireMove(roomId)

        assertThat(moveTimeoutJob.run()).isEqualTo(1)
        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(count(RoomSessionEventTable) { RoomSessionEventTable.code eq "player-passed" }).isEqualTo(1)
    }

    private fun perMove(seconds: Int, rounds: Int = 3) =
        nothingSettings(rounds = rounds, clockSeconds = seconds)

    private fun expireMove(roomId: UUID) {
        db {
            RoomSessionTable.update({ (RoomSessionTable.roomId eq roomId) and (RoomSessionTable.status eq "IN_PROGRESS") }) {
                it[moveDeadline] = Instant.now().minusSeconds(5)
            }
        }
    }
}
