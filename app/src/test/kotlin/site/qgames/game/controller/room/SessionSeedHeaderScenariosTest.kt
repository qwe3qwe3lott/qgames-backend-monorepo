package site.qgames.game.controller.room

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB

/**
 * Seed партии из заголовка `X-Qgames-Seed` (e2e-сценарии на локальном стенде). В тестах флаг
 * `seed-header-enabled` включён, как в профиле local; в проде он выключен и заголовок игнорируется.
 */
class SessionSeedHeaderScenariosTest : GameIntegrationTest() {

    @Test
    fun `the seed header sets the seed of the game`() {
        val roomId = roomWith(ALICE, BOB)

        startWithSeed(roomId, "-42").andExpect { status { isOk() } }

        assertThat(roomSessionRepository.findById(sessionId(roomId))!!.seed).isEqualTo(-42L)
    }

    @Test
    fun `a non-numeric seed is a validation error and the game does not start`() {
        val roomId = roomWith(ALICE, BOB)

        startWithSeed(roomId, "abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }

        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.WAITING)
        assertThat(roomSessionRepository.findLatest(roomId)).isNull()
    }

    private fun startWithSeed(roomId: UUID, seed: String): ResultActionsDsl =
        mockMvc.post("/api/rooms/$roomId/session") {
            asUser(ALICE)
            header("X-Qgames-Seed", seed)
        }
}
