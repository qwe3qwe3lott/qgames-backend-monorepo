package site.qgames.adminauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.adminauth.BaseIntegrationTest
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.support.TestData
import site.qgames.adminauth.util.Hashing
import java.time.Duration
import java.time.Instant
import java.util.stream.Stream

class RefreshTokenScenariosTest : BaseIntegrationTest() {

    /** Получает токены свежей сессии и возвращает refresh-токен, который будет предъявлен. */
    fun interface Setup {
        fun RefreshTokenScenariosTest.prepare(session: AuthResponse): String
    }

    @Test
    fun `refresh rotates tokens and every new token keeps working`() {
        val session = startSession()

        val first = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.body<AuthResponse>()
        val second = refresh(first.tokens.refreshToken).andExpect { status { isOk() } }.body<AuthResponse>()

        assertThat(listOf(session, first, second).map { it.tokens.refreshToken }).doesNotHaveDuplicates()
        assertThat(second.admin.id).isEqualTo(TestData.Root.ID)
        assertThat(verifiedClaims(second.tokens.accessToken).subject).isEqualTo(TestData.Root.ID.toString())
        assertThat(countRows("refresh_token WHERE revoked_at IS NULL")).isEqualTo(1)
    }

    @Test
    fun `rotation gives the new refresh token the full lifetime`() {
        val session = startSession()
        // Сессия почти истекла: до конца срока refresh-токена осталась минута.
        jdbcClient.sql("UPDATE refresh_token SET expires_at = now() + interval '1 minute'").update()

        val rotated = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.body<AuthResponse>()

        assertThat(Duration.between(Instant.now(), rotated.tokens.refreshTokenExpiresAt))
            .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15))
        val fullLifetime = jdbcClient
            .sql("SELECT expires_at - created_at = interval '15 minutes' FROM refresh_token WHERE token_hash = :hash")
            .param("hash", Hashing.sha256Hex(rotated.tokens.refreshToken))
            .query(Boolean::class.java)
            .single()
        assertThat(fullLifetime).isTrue()
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
        val rotated = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.body<AuthResponse>()
        moveRotationToPast(secondsAfterRotation)

        val replay = refresh(session.tokens.refreshToken)

        if (accepted) {
            // Вторая вкладка получила свою пару той же сессии; обе ветки продолжают работать.
            val parallel = replay.andExpect { status { isOk() } }.body<AuthResponse>()
            assertThat(parallel.admin.id).isEqualTo(TestData.Root.ID)
            refresh(rotated.tokens.refreshToken).andExpect { status { isOk() } }
            refresh(parallel.tokens.refreshToken).andExpect { status { isOk() } }
        } else {
            replay.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") }
            }
            refresh(rotated.tokens.refreshToken).andExpect { jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") } }
            assertThat(countRows("refresh_token WHERE revoked_at IS NULL")).isZero()
        }
    }

    @Test
    fun `reuse revokes only the compromised session`() {
        val stolen = startSession()
        val otherDevice = startSession(TestData.Root.LOGIN)
        refresh(stolen.tokens.refreshToken).andExpect { status { isOk() } }
        moveRotationToPast(60)

        refresh(stolen.tokens.refreshToken).andExpect { jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") } }

        refresh(otherDevice.tokens.refreshToken).andExpect { status { isOk() } }
    }

    /** Сдвигает момент ротации всех ротированных токенов в прошлое. */
    private fun moveRotationToPast(seconds: Long) {
        jdbcClient.sql(
            """
            UPDATE refresh_token
            SET rotated_at = rotated_at - make_interval(secs => :seconds), revoked_at = revoked_at - make_interval(secs => :seconds)
            WHERE rotated_at IS NOT NULL
            """,
        ).param("seconds", seconds).update()
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
    fun `repeated logout is idempotent`() {
        val session = startSession()

        logout(session.tokens.refreshToken).andExpect { status { isOk() } }
        val revokedAt = revokedAt()
        logout(session.tokens.refreshToken).andExpect { status { isOk() } }

        assertThat(revokedAt).isNotNull()
        assertThat(revokedAt()).isEqualTo(revokedAt)
    }

    private fun revokedAt(): String? =
        jdbcClient.sql("SELECT revoked_at::text FROM refresh_token").query(String::class.java).single()

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
                    jdbcClient.sql("UPDATE refresh_token SET expires_at = now() - interval '1 second'").update()
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
                    val rotated = refresh(session.tokens.refreshToken).andExpect { status { isOk() } }.body<AuthResponse>()
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
