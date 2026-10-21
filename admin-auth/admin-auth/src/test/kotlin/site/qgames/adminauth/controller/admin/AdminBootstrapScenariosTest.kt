package site.qgames.adminauth.controller.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.DefaultApplicationArguments
import site.qgames.adminauth.BaseIntegrationTest
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.support.TestData
import java.util.stream.Stream

/**
 * Бутстрап первого администратора. При старте тестового контекста он выключен (application-test.yml),
 * поэтому сервис вызывается напрямую.
 */
class AdminBootstrapScenariosTest : BaseIntegrationTest() {

    @Test
    fun `first admin is created when there are no admins`() {
        removeAllAdmins()

        val created = adminBootstrapService.bootstrap("admin", "bootstrap-password")

        assertThat(created).isTrue()
        val (storedLogin, hash) = jdbcClient.sql("SELECT login, password_hash FROM admin")
            .query { rs, _ -> rs.getString("login") to rs.getString("password_hash") }
            .single()
        assertThat(storedLogin).isEqualTo("admin")
        assertThat(hash).startsWith("\$argon2id\$").doesNotContain("bootstrap-password")
        assertThat(passwordEncoder.matches("bootstrap-password", hash)).isTrue()
        val session = login("admin", "bootstrap-password").andExpect { status { isOk() } }.body<AuthResponse>()
        assertThat(session.admin.login).isEqualTo("admin")
    }

    @Test
    fun `repeated bootstrap after a restart changes nothing`() {
        removeAllAdmins()
        adminBootstrapService.bootstrap("admin", "bootstrap-password")

        val createdAgain = adminBootstrapService.bootstrap("admin", "another-password")

        assertThat(createdAgain).isFalse()
        assertThat(countRows("admin")).isEqualTo(1)
        login("admin", "bootstrap-password").andExpect { status { isOk() } }
    }

    @ParameterizedTest(name = "bootstrap {0}/{1} with existing admins")
    @MethodSource("bootstrapAccounts")
    fun `existing admins are left untouched`(bootstrapLogin: String, bootstrapPassword: String) {
        val before = adminsSnapshot()

        val created = adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword)

        assertThat(created).isFalse()
        assertThat(adminsSnapshot()).isEqualTo(before)
        assertThat(countRows("admin")).isEqualTo(TestData.FIXTURE_ADMIN_COUNT)
    }

    @ParameterizedTest(name = "login={0}, password={1}")
    @MethodSource("disabledSettings")
    fun `bootstrap without settings does nothing`(bootstrapLogin: String?, bootstrapPassword: String?) {
        removeAllAdmins()

        val created = adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword)

        assertThat(created).isFalse()
        assertThat(countRows("admin")).isZero()
    }

    @Test
    fun `startup run without bootstrap properties does nothing`() {
        removeAllAdmins()

        adminBootstrapService.run(DefaultApplicationArguments())

        assertThat(countRows("admin")).isZero()
    }

    @ParameterizedTest(name = "login={0}, password={1}")
    @MethodSource("partialSettings")
    fun `only one of login and password is a configuration error`(bootstrapLogin: String?, bootstrapPassword: String?) {
        removeAllAdmins()

        assertThatThrownBy { adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ADMIN_BOOTSTRAP_LOGIN")
        assertThat(countRows("admin")).isZero()
    }

    private fun removeAllAdmins() {
        jdbcClient.sql("TRUNCATE admin CASCADE").update()
    }

    private fun adminsSnapshot(): List<Map<String, Any?>> =
        jdbcClient.sql("SELECT id, login, password_hash, created_at FROM admin ORDER BY id").query().listOfRows()

    companion object {
        @JvmStatic
        fun bootstrapAccounts(): Stream<Arguments> = Stream.of(
            arguments("admin", "admin"),
            // Логин фикстуры: даже совпадающий логин не перезаписывает пароль.
            arguments(TestData.Root.LOGIN, "new-password"),
        )

        @JvmStatic
        fun disabledSettings(): Stream<Arguments> = Stream.of(
            arguments(null, null),
            arguments("", ""),
            arguments(" ", " "),
        )

        @JvmStatic
        fun partialSettings(): Stream<Arguments> = Stream.of(
            arguments("admin", null),
            arguments("admin", ""),
            arguments(null, "admin"),
        )
    }
}
