package site.qgames.adminauth.controller.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.get
import site.qgames.adminauth.BaseIntegrationTest
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import site.qgames.adminauth.support.TestData
import site.qgames.common.web.header.QgamesHeaders
import java.sql.Timestamp
import java.util.UUID
import java.util.stream.Stream

class AdminScenariosTest : BaseIntegrationTest() {

    @Test
    fun `current admin is taken from X-User-Id`() {
        val admin = getJson("/api/admins/me", TestData.Root.ID).andExpect { status { isOk() } }.body<AdminResponse>()

        assertThat(admin).isEqualTo(AdminResponse(TestData.Root.ID, TestData.Root.LOGIN, TestData.Root.CREATED_AT))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unresolvedAdmins")
    fun `current admin cannot be resolved`(case: String, userIdHeader: String?, expectedStatus: HttpStatus, expectedCode: String) {
        mockMvc.get("/api/admins/me") {
            userIdHeader?.let { header(QgamesHeaders.USER_ID, it) }
        }.andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
    }

    @Test
    fun `admins are listed by creation time, then by id`() {
        // Создан одновременно с Root, но id меньше — должен идти перед ним.
        val sameTimeAsRoot = UUID.fromString("01990000-0000-7000-8000-0000000000a0")
        jdbcClient.sql("INSERT INTO admin (id, login, password_hash, created_at) VALUES (:id, 'support', 'hash', :createdAt)")
            .param("id", sameTimeAsRoot)
            .param("createdAt", Timestamp.from(TestData.Root.CREATED_AT))
            .update()

        val list = getJson("/api/admins", TestData.Root.ID).andExpect { status { isOk() } }.body<AdminListResponse>()

        assertThat(list.admins.map { it.id }).containsExactly(TestData.Operator.ID, sameTimeAsRoot, TestData.Root.ID)
        assertThat(list.admins.first()).isEqualTo(
            AdminResponse(TestData.Operator.ID, TestData.Operator.LOGIN, TestData.Operator.CREATED_AT),
        )
        assertThat(countRows("admin")).isEqualTo(TestData.FIXTURE_ADMIN_COUNT + 1)
    }

    @Test
    fun `admin list does not expose password hashes`() {
        val json = getJson("/api/admins", TestData.Root.ID).andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertThat(json).doesNotContain("argon2", "password")
    }

    companion object {
        @JvmStatic
        fun unresolvedAdmins(): Stream<Arguments> = Stream.of(
            arguments("unknown admin id", UUID.randomUUID().toString(), HttpStatus.NOT_FOUND, "ADMIN_NOT_FOUND"),
            arguments("no X-User-Id header", null, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"),
            arguments("malformed X-User-Id header", "not-a-uuid", HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"),
        )
    }
}
