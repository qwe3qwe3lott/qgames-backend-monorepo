package site.qgames.userauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.support.TestData
import java.util.stream.Stream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import site.qgames.userauth.table.auth.RefreshTokenTable
import java.time.Instant

class RefreshTokenScenariosTest : UserAuthIntegrationTest() {

    /** Получает токены свежей сессии и возвращает refresh-токен, который будет предъявлен. */
    fun interface Setup {
        fun RefreshTokenScenariosTest.prepare(session: AuthSession): String
    }

    @Test
    fun `refresh rotates tokens and every new token keeps working`() {
        val session = startSession()

        val first = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.session()
        val second = refresh(first.tokens.refreshToken).andExpect { status { isOk() } }.session()

        assertThat(listOf(session, first, second).map { it.tokens.refreshToken }).doesNotHaveDuplicates()
        assertThat(second.user.id).isEqualTo(TestData.PasswordOnly.ID)
        assertThat(count(RefreshTokenTable) { RefreshTokenTable.revokedAt.isNull() }).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRefreshes")
    fun `rejected refresh scenario`(case: String, setup: Setup, expectedCode: String) {
        val session = startSession()
        val token = with(setup) { prepare(session) }

        refresh(token).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(expectedCode) }
        }
    }

    @ParameterizedTest(name = "replay {0} s after rotation, accepted: {1}")
    @MethodSource("replaysAfterRotation")
    fun `replay of a rotated token is tolerated only within the grace window`(secondsAfterRotation: Long, accepted: Boolean) {
        val session = startSession()
        val rotated = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.session()
        moveRotationToPast(secondsAfterRotation)

        val replay = refresh(session.tokens.refreshToken)

        if (accepted) {
            // Вторая вкладка получила свою пару той же сессии; обе ветки продолжают работать.
            val parallel = replay.andExpect { status { isOk() } }.session()
            assertThat(parallel.user.id).isEqualTo(TestData.PasswordOnly.ID)
            refresh(rotated.tokens.refreshToken).andExpect { status { isOk() } }
            refresh(parallel.tokens.refreshToken).andExpect { status { isOk() } }
        } else {
            replay.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") }
            }
            refresh(rotated.tokens.refreshToken).andExpect { jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") } }
            assertThat(count(RefreshTokenTable) { RefreshTokenTable.revokedAt.isNull() }).isZero()
        }
    }

    /** Сдвигает момент ротации всех ротированных токенов в прошлое. */
    private fun moveRotationToPast(seconds: Long) {
        db {
            RefreshTokenTable.selectAll().where { RefreshTokenTable.rotatedAt.isNotNull() }.toList().forEach { row ->
                RefreshTokenTable.update({ RefreshTokenTable.id eq row[RefreshTokenTable.id] }) {
                    it[rotatedAt] = row[RefreshTokenTable.rotatedAt]!!.minusSeconds(seconds)
                    it[revokedAt] = row[RefreshTokenTable.revokedAt]?.minusSeconds(seconds)
                }
            }
        }
    }

    @Test
    fun `logout ends only its own session`() {
        val firstDevice = startSession()
        val secondDevice = startSession()

        logout(firstDevice.tokens.refreshToken).andExpect { status { isOk() } }

        refresh(firstDevice.tokens.refreshToken).andExpect { status { isUnauthorized() } }
        refresh(secondDevice.tokens.refreshToken).andExpect { status { isOk() } }
    }

    @Test
    fun `logout is idempotent for unknown tokens`() {
        logout("never-issued-token").andExpect { status { isOk() } }
    }

    companion object {
        @JvmStatic
        fun rejectedRefreshes(): Stream<Arguments> = Stream.of(
            arguments(
                "token that was never issued",
                Setup { "never-issued-token" },
                "INVALID_REFRESH_TOKEN",
            ),
            arguments(
                "expired token",
                Setup { session ->
                    db { RefreshTokenTable.update { it[expiresAt] = Instant.now().minusSeconds(1) } }
                    session.tokens.refreshToken
                },
                "INVALID_REFRESH_TOKEN",
            ),
            arguments(
                "token of a logged out session",
                Setup { session ->
                    logout(session.tokens.refreshToken).andExpect { status { isOk() } }
                    session.tokens.refreshToken
                },
                "REFRESH_TOKEN_REUSED",
            ),
            arguments(
                "token rotated longer ago than the grace window",
                Setup { session ->
                    refresh(session.tokens.refreshToken).andExpect { status { isOk() } }
                    moveRotationToPast(60)
                    session.tokens.refreshToken
                },
                "REFRESH_TOKEN_REUSED",
            ),
            arguments(
                "token rotated within the grace window, but the session was logged out",
                Setup { session ->
                    val rotated = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.session()
                    logout(rotated.tokens.refreshToken).andExpect { status { isOk() } }
                    session.tokens.refreshToken
                },
                "REFRESH_TOKEN_REUSED",
            ),
        )

        @JvmStatic
        fun replaysAfterRotation(): Stream<Arguments> = Stream.of(
            arguments(0L, true),
            arguments(9L, true),
            arguments(11L, false),
        )
    }
}
