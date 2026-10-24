package site.qgames.game.service.session

import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.GameIntegrationTest
import site.qgames.game.cache.session.CaffeineSessionStateCache
import site.qgames.game.cache.session.SessionStateCache
import site.qgames.game.config.GameProperties
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.SessionStateModel
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.ViewerKey
import java.util.UUID
import java.util.stream.Stream

/**
 * Сборка состояния партии из журнала через кеш состояний зрителей: промах — свёртка с нуля, попадание на seq K —
 * доигрываются только события K+1…N, результат кладётся обратно.
 */
class SessionStateCacheScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var gameRegistry: GameRegistry

    @Autowired
    private lateinit var properties: GameProperties

    @Autowired
    private lateinit var sessionEngine: SessionEngine

    @Test
    fun `cache miss folds the journal from scratch and stores the result`() {
        val session = sessionWith33Events()
        val cache = CaffeineSessionStateCache(properties)

        val state = service(cache).state(session, ViewerKey.Full)

        assertThat(state.seq).isEqualTo(33)
        assertThat(state.document).isEqualTo(fullDocument(session.roomId))
        verify(exactly = 1) { roomSessionEventRepository.findRange(session.id, 1, 33) }
        assertThat(cache.get(session.id, ViewerKey.Full)).isEqualTo(state)
    }

    @Test
    fun `cache hit on seq 7 replays only events 8 to 33`() {
        val session = sessionWith33Events()
        val cache = CaffeineSessionStateCache(properties)
        val atSeven = service(cache).state(session.copy(lastSeq = 7), ViewerKey.Player(BOB))

        val state = service(cache).state(session, ViewerKey.Player(BOB))

        verify(exactly = 1) { roomSessionEventRepository.findRange(session.id, 8, 33) }
        assertThat(atSeven.seq).isEqualTo(7)
        assertThat(state.seq).isEqualTo(33)
        assertThat(state.document).isEqualTo(service(CaffeineSessionStateCache(properties)).state(session, ViewerKey.Player(BOB)).document)
        assertThat(cache.get(session.id, ViewerKey.Player(BOB))!!.seq).isEqualTo(33)
    }

    @Test
    fun `newer cached state is never replaced by an older one`() {
        val session = sessionWith33Events()
        val cache = CaffeineSessionStateCache(properties)
        val latest = service(cache).state(session, ViewerKey.Public)

        cache.put(session.id, ViewerKey.Public, SessionStateModel(7, latest.document))

        assertThat(cache.get(session.id, ViewerKey.Public)).isEqualTo(latest)
    }

    /** Снапшот зрителя — свёртка его маскированных событий; она совпадает с маской полной свёртки. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("viewers")
    fun `fold of masked events equals masked full fold`(viewer: ViewerKey) {
        val session = sessionWith33Events()

        val ofMasked = service(CaffeineSessionStateCache(properties)).state(session, viewer).document
        val full = fullDocument(session.roomId)

        assertThat(ofMasked).isEqualTo(mask(full, viewer))
    }

    /** Партия двоих без таймера: session-started + 8 ходов по 4 события = 33 события. */
    private fun sessionWith33Events(): RoomSessionModel {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(rounds = 10))
        repeat(4) {
            pass(ALICE, roomId)
            pass(BOB, roomId)
        }
        val session = roomSessionRepository.findById(sessionId(roomId))!!
        assertThat(session.lastSeq).isEqualTo(33)
        return session
    }

    private fun service(cache: SessionStateCache) = SessionStateService(cache, roomSessionEventRepository, gameRegistry, sessionEngine)

    /** Маска полного документа: чужие варианты ходов — пустой список. */
    @Suppress("UNCHECKED_CAST")
    private fun mask(full: Map<String, Any?>, viewer: ViewerKey): Map<String, Any?> {
        val state = full.getValue("state") as Map<String, Any?>
        val players = (state.getValue("players") as List<Map<String, Any?>>)
            .map { if (viewer.sees(UUID.fromString(it["id"] as String))) it else it + ("moveOptions" to emptyList<Any>()) }
        return full + ("state" to state + ("players" to players))
    }

    companion object {
        @JvmStatic
        fun viewers(): Stream<ViewerKey> =
            Stream.of(ViewerKey.Full, ViewerKey.Public, ViewerKey.Player(ALICE), ViewerKey.Player(BOB), ViewerKey.Player(UUID.randomUUID()))
    }
}
