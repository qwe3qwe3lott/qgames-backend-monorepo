package site.qgames.userauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.service.user.DevUsersBootstrapService
import site.qgames.userauth.support.TestData
import site.qgames.userauth.table.user.UserTable
import java.util.stream.Stream

/**
 * Тестовые игроки локального стенда. Профиль test их не задаёт, поэтому при старте контекста бутстрап выключен,
 * и сервис вызывается напрямую.
 */
class DevUsersBootstrapScenariosTest : UserAuthIntegrationTest() {

    @Autowired
    private lateinit var devUsersBootstrapService: DevUsersBootstrapService

    @Test
    fun `dev users are created and log in with the shared password`() {
        val created = devUsersBootstrapService.bootstrap("dev", 3, "dev-password")

        assertThat(created).isEqualTo(3)
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT + 3)
        (1..3).forEach { n ->
            val session = login("dev$n", "dev-password").andExpect { status { isOk() } }.session()
            assertThat(session.user.login).isEqualTo("dev$n")
            assertThat(session.user.nickname).isEqualTo("dev$n")
            assertThat(session.user.loginMethods).containsExactly(LoginMethod.PASSWORD)
        }
        assertThat(publishedProfileEvents().map { it.nickname }).containsExactly("dev1", "dev2", "dev3")
    }

    @Test
    fun `repeated bootstrap creates only missing users and keeps passwords`() {
        devUsersBootstrapService.bootstrap("dev", 2, "dev-password")

        val created = devUsersBootstrapService.bootstrap("dev", 3, "another-password")

        assertThat(created).isEqualTo(1)
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT + 3)
        login("dev1", "dev-password").andExpect { status { isOk() } }
        login("dev3", "another-password").andExpect { status { isOk() } }
    }

    @Test
    fun `startup run without dev users properties does nothing`() {
        devUsersBootstrapService.run(DefaultApplicationArguments())

        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(db { UserTable.selectAll().where { UserTable.nickname eq "qwe3qwe1" }.count() }).isZero()
    }

    @ParameterizedTest(name = "prefix={0}, count={1}, password={2}")
    @MethodSource("disabledSettings")
    fun `bootstrap with zero count does nothing`(prefix: String?, count: Int, password: String?) {
        val created = devUsersBootstrapService.bootstrap(prefix, count, password)

        assertThat(created).isZero()
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @ParameterizedTest(name = "prefix={0}, password={1}")
    @MethodSource("partialSettings")
    fun `count without prefix or password is a configuration error`(prefix: String?, password: String?) {
        assertThatThrownBy { devUsersBootstrapService.bootstrap(prefix, 2, password) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("qgames.auth.dev-users")
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    companion object {
        @JvmStatic
        fun disabledSettings(): Stream<Arguments> = Stream.of(
            arguments(null, 0, null),
            arguments("dev", 0, "dev-password"),
            arguments("dev", -1, "dev-password"),
        )

        @JvmStatic
        fun partialSettings(): Stream<Arguments> = Stream.of(
            arguments("dev", null),
            arguments("dev", " "),
            arguments(null, "dev-password"),
            arguments("", "dev-password"),
        )
    }
}
