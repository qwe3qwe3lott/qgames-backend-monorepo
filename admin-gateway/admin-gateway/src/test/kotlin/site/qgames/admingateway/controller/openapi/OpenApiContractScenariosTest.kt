package site.qgames.admingateway.controller.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.web.servlet.get
import site.qgames.admingateway.BaseIntegrationTest
import tools.jackson.databind.JsonNode

/**
 * OpenAPI admin-gateway — вход для генератора клиента админки (Orval): только публичные DTO, стабильный порядок ключей,
 * обязательные поля по nullability Kotlin. Документ доступен без аутентификации.
 */
class OpenApiContractScenariosTest : BaseIntegrationTest() {

    /** Снапшот API на фронте не должен меняться без изменения контракта (springdoc.writer-with-order-by-keys). */
    @ParameterizedTest(name = "{0}")
    @CsvSource("paths", "components.schemas")
    fun `openapi keys are sorted`(section: String) {
        val node = section.split('.').fold(apiDocs()) { node, key -> node.path(key) }

        val keys = node.propertyNames().toList()

        assertThat(keys).isNotEmpty().isSorted()
    }

    @Test
    fun `public contract exposes only admin-gateway endpoints and dtos`() {
        val docs = apiDocs()

        assertThat(docs.path("paths").propertyNames().toList()).containsExactly(
            "/api/admins",
            "/api/admins/me",
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/refresh",
        )
        assertThat(docs.path("components").path("schemas").propertyNames().toList())
            .contains("AdminListResponse", "AdminLoginRequest", "AdminResponse")
            .doesNotContain("AuthResponse", "TokenPair", "LoginRequest", "RefreshTokenRequest")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "AdminLoginRequest, login|password",
        "AdminResponse, createdAt|id|login",
        "AdminListResponse, admins",
    )
    fun `dto fields are required`(schema: String, fields: String) {
        val required = apiDocs().path("components").path("schemas").path(schema).path("required")

        assertThat((0 until required.size()).map { required.get(it).asString() })
            .containsExactlyInAnyOrder(*fields.split('|').toTypedArray())
    }

    private fun apiDocs(): JsonNode =
        jsonMapper.readTree(mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }.andReturn().response.contentAsString)
}
