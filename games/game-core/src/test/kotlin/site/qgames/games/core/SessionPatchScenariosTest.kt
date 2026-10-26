package site.qgames.games.core

import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.games.core.SessionEvents.delete
import site.qgames.games.core.SessionEvents.id
import site.qgames.games.core.SessionEvents.info
import site.qgames.games.core.SessionEvents.patch
import site.qgames.games.core.SessionEvents.path
import site.qgames.games.core.SessionEvents.put

/** Общий применитель событий: PUT, PATCH, DELETE, INFO по путям с ключами и `{id}`, ошибки пути. */
class SessionPatchScenariosTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    fun `event changes the document`(case: String, event: SessionEvent, expected: Map<String, Any?>) {
        val result = SessionPatch.apply(DOCUMENT, event)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `source document is not changed and the key order is kept`() {
        val before = DOCUMENT.toString()

        val result = SessionPatch.apply(DOCUMENT, put("c", path("state", "timer"), mapOf("status" to "STOPPED")))

        assertThat(DOCUMENT.toString()).isEqualTo(before)
        assertThat(result.getValue("state").let { (it as Map<*, *>).keys }).containsExactly("players", "timer", "moves")
    }

    @Test
    fun `first event replaces the whole document, later events fold on it`() {
        val started = put("session-started", emptyList(), DOCUMENT)

        val folded = SessionPatch.fold(null, listOf(started, patch("session-finished", emptyList(), mapOf("status" to "FINISHED"))))

        assertThat(folded).isEqualTo(DOCUMENT + ("status" to "FINISHED"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `event that does not fit the document is an error`(case: String, document: Map<String, Any?>?, event: SessionEvent) {
        assertThatThrownBy { SessionPatch.apply(document, event) }
            .isInstanceOf(SessionPatchException::class.java)
            .hasMessageContaining(event.code)
    }

    companion object {
        private val ALICE = player("a", listOf(mapOf("type" to "PASS", "data" to null)))
        private val BOB = player("b", emptyList())

        private val DOCUMENT: Map<String, Any?> = linkedMapOf(
            "status" to "IN_PROGRESS",
            "meta" to mapOf("game" to mapOf("code" to "nothing")),
            "state" to linkedMapOf(
                "players" to listOf(ALICE, BOB),
                "timer" to linkedMapOf("status" to "RUNNING", "deadline" to "2026-10-21T10:00:30Z", "pausedAt" to null),
                "moves" to 0,
            ),
        )

        private fun player(id: String, options: List<Any?>) = linkedMapOf("id" to id, "moveOptions" to options)

        @Suppress("UNCHECKED_CAST")
        private fun withState(vararg changes: Pair<String, Any?>): Map<String, Any?> =
            DOCUMENT + ("state" to ((DOCUMENT["state"] as Map<String, Any?>) + changes))

        @JvmStatic
        fun changes(): Stream<Arguments> = Stream.of(
            arguments(
                "PUT replaces a value",
                put("timer-stopped", path("state", "timer"), mapOf("status" to "STOPPED", "deadline" to null, "pausedAt" to null)),
                withState("timer" to mapOf("status" to "STOPPED", "deadline" to null, "pausedAt" to null)),
            ),
            arguments("PUT creates a missing key", put("c", path("state", "turns"), mapOf("round" to 1)), withState("turns" to mapOf("round" to 1))),
            arguments("PUT writes null", put("c", path("state", "timer"), null), withState("timer" to null)),
            arguments(
                "PUT replaces a field of an array element by id",
                put("move-options-changed", path("state", "players", id("b"), "moveOptions"), listOf(mapOf("type" to "PASS"))),
                withState("players" to listOf(ALICE, player("b", listOf(mapOf("type" to "PASS"))))),
            ),
            arguments(
                "PUT by a missing id appends to the array",
                put("c", path("state", "players", id("c")), player("c", emptyList())),
                withState("players" to listOf(ALICE, BOB, player("c", emptyList()))),
            ),
            arguments(
                "PUT by an existing id replaces the element in place",
                put("c", path("state", "players", id("a")), player("a", emptyList())),
                withState("players" to listOf(player("a", emptyList()), BOB)),
            ),
            arguments(
                "PATCH is shallow: fields are replaced, nested values as a whole, null is a value",
                patch("game-paused", path("state", "timer"), mapOf("status" to "PAUSED", "deadline" to null, "extra" to mapOf("x" to 1))),
                withState("timer" to mapOf("status" to "PAUSED", "deadline" to null, "pausedAt" to null, "extra" to mapOf("x" to 1))),
            ),
            arguments("PATCH of the root", patch("session-cancelled", emptyList(), mapOf("status" to "CANCELLED")), DOCUMENT + ("status" to "CANCELLED")),
            arguments("PATCH of the state", patch("player-passed", path("state"), mapOf("moves" to 1)), withState("moves" to 1)),
            arguments("DELETE removes a key", delete("c", path("state", "timer")), withState().let { it + ("state" to (it["state"] as Map<*, *>).minus("timer")) }),
            arguments("DELETE removes an array element by id", delete("c", path("state", "players", id("a"))), withState("players" to listOf(BOB))),
            arguments("INFO does not change the document", info("player-passed", mapOf("playerId" to "a", "timeout" to true)), DOCUMENT),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            arguments("PUT under a missing key", DOCUMENT, put("c", path("state", "turns", "round"), 1)),
            arguments("PUT under a missing id", DOCUMENT, put("c", path("state", "players", id("x"), "moveOptions"), emptyList<Any>())),
            arguments("PUT of a new element without the same id", DOCUMENT, put("c", path("state", "players", id("x")), player("y", emptyList()))),
            arguments("PUT by id into an object", DOCUMENT, put("c", path("state", id("a")), 1)),
            arguments("PUT by key into an array", DOCUMENT, put("c", path("state", "players", "a"), 1)),
            arguments("PUT replaces the root with a non-object", DOCUMENT, put("c", emptyList(), listOf(1))),
            arguments("PATCH of a missing key", DOCUMENT, patch("c", path("state", "turns"), mapOf("round" to 2))),
            arguments("PATCH of a non-object", DOCUMENT, patch("c", path("state", "moves"), mapOf("x" to 1))),
            arguments("PATCH with a non-object value", DOCUMENT, SessionEvent("c", SessionEventMethod.PATCH, path("state"), 1)),
            arguments("DELETE of a missing key", DOCUMENT, delete("c", path("state", "turns"))),
            arguments("DELETE of a missing id", DOCUMENT, delete("c", path("state", "players", id("x")))),
            arguments("DELETE of the root", DOCUMENT, delete("c", emptyList())),
            arguments("first event is not PUT []", null, patch("c", emptyList(), mapOf("status" to "FINISHED"))),
        )
    }
}
