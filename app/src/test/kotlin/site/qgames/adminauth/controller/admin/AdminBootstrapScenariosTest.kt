package site.qgames.adminauth.controller.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.DefaultApplicationArguments
import site.qgames.adminauth.AdminAuthIntegrationTest
import site.qgames.adminauth.support.TestData
import site.qgames.adminauth.table.admin.AdminTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.stream.Stream

/**
 * Бутстрап первого администратора. При старте тестового контекста он выключен (application-test.yml),
 * поэтому сервис вызывается напрямую.
 */
class AdminBootstrapScenariosTest : AdminAuthIntegrationTest() {

    @Test
    fun `first admin is created when there are no admins`() {
        removeAllAdmins()

        val created = adminBootstrapService.bootstrap("admin", "bootstrap-password")

        assertThat(created).isTrue()
        val (storedLogin, hash) = db { AdminTable.selectAll().single().let { it[AdminTable.login] to it[AdminTable.passwordHash] } }
        assertThat(storedLogin).isEqualTo("admin")
        assertThat(hash).startsWith("\$argon2id\$").doesNotContain("bootstrap-password")
        assertThat(passwordEncoder.matches("bootstrap-password", hash)).isTrue()
        val session = login("admin", "bootstrap-password").andExpect { status { isOk() } }.session()
        assertThat(session.admin.login).isEqualTo("admin")
    }

    @Test
    fun `repeated bootstrap after a restart changes nothing`() {
        removeAllAdmins()
        adminBootstrapService.bootstrap("admin", "bootstrap-password")

        val createdAgain = adminBootstrapService.bootstrap("admin", "another-password")

        assertThat(createdAgain).isFalse()
        assertThat(count(AdminTable)).isEqualTo(1)
        login("admin", "bootstrap-password").andExpect { status { isOk() } }
    }

    @ParameterizedTest(name = "bootstrap {0}/{1} with existing admins")
    @MethodSource("bootstrapAccounts")
    fun `existing admins are left untouched`(bootstrapLogin: String, bootstrapPassword: String) {
        val before = adminsSnapshot()

        val created = adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword)

        assertThat(created).isFalse()
        assertThat(adminsSnapshot()).isEqualTo(before)
        assertThat(count(AdminTable)).isEqualTo(TestData.FIXTURE_ADMIN_COUNT)
    }

    @ParameterizedTest(name = "login={0}, password={1}")
    @MethodSource("disabledSettings")
    fun `bootstrap without settings does nothing`(bootstrapLogin: String?, bootstrapPassword: String?) {
        removeAllAdmins()

        val created = adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword)

        assertThat(created).isFalse()
        assertThat(count(AdminTable)).isZero()
    }

    @Test
    fun `startup run without bootstrap properties does nothing`() {
        removeAllAdmins()

        adminBootstrapService.run(DefaultApplicationArguments())

        assertThat(count(AdminTable)).isZero()
    }

    @ParameterizedTest(name = "login={0}, password={1}")
    @MethodSource("partialSettings")
    fun `only one of login and password is a configuration error`(bootstrapLogin: String?, bootstrapPassword: String?) {
        removeAllAdmins()

        assertThatThrownBy { adminBootstrapService.bootstrap(bootstrapLogin, bootstrapPassword) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ADMIN_BOOTSTRAP_LOGIN")
        assertThat(count(AdminTable)).isZero()
    }

    private fun removeAllAdmins() {
        db { AdminTable.deleteAll() }
    }

    private fun adminsSnapshot(): List<List<Any?>> = db {
        AdminTable.selectAll().orderBy(AdminTable.id to SortOrder.ASC).map { row -> AdminTable.columns.map { row[it] } }
    }

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
