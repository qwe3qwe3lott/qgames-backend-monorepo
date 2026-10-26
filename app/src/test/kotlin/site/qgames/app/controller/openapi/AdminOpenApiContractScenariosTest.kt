package site.qgames.app.controller.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.web.servlet.get
import site.qgames.BaseIntegrationTest
import tools.jackson.databind.JsonNode

/**
 * OpenAPI группы admin (`/v3/api-docs/admin`) — вход для генератора клиента админки (Orval): DTO контракта admin-auth без внутренних DTO
 * сессии, стабильный порядок ключей, обязательные поля по nullability Kotlin. Документ доступен без аутентификации.
 */
class AdminOpenApiContractScenariosTest : BaseIntegrationTest() {

    /** Сгенерированный клиент на фронте не должен меняться без изменения контракта (springdoc.writer-with-order-by-keys). */
    @ParameterizedTest(name = "{0}")
    @CsvSource("paths", "components.schemas")
    fun `openapi keys are sorted`(section: String) {
        val node = section.split('.').fold(apiDocs()) { node, key -> node.path(key) }

        val keys = node.propertyNames().toList()

        assertThat(keys).isNotEmpty().isSorted()
    }

    @Test
    fun `admin group exposes only admin endpoints and dtos`() {
        val docs = apiDocs()

        assertThat(docs.path("paths").propertyNames().toList()).containsExactly(
            "/api/admin/admins",
            "/api/admin/admins/me",
            "/api/admin/auth/login",
            "/api/admin/auth/logout",
            "/api/admin/auth/refresh",
        )
        assertThat(docs.path("components").path("schemas").propertyNames().toList())
            .containsExactly("AdminListResponse", "AdminResponse", "LoginRequest", "ProblemDetail")
    }

    @Test
    fun `no service unavailable responses`() {
        val docs = apiDocs()

        docs.path("paths").properties().forEach { (path, operations) ->
            operations.properties().forEach { (method, operation) ->
                assertThat(operation.path("responses").has("503")).describedAs("$method $path").isFalse()
            }
        }
        assertThat(docs.toString()).doesNotContain("SERVICE_UNAVAILABLE", "X-User-Id", "gateway", "/api/auth/refresh")
    }

    /** Ручки отдают DTO admin-auth, но токены остаются в куках: внутренние DTO сессии наружу не выходят. */
    @Test
    fun `auth tokens are not part of the public contract`() {
        val docs = apiDocs()

        assertThat(docs.path("components").path("schemas").propertyNames().toList())
            .doesNotContain("AuthResponse", "TokenPair", "RefreshTokenRequest")
        listOf("/api/admin/auth/refresh", "/api/admin/auth/logout").forEach { path ->
            assertThat(docs.path("paths").path(path).path("post").has("requestBody")).describedAs(path).isFalse()
        }
        assertThat(docs.path("paths").path("/api/admin/auth/login").path("post").path("requestBody").path("content")
            .path("application/json").path("schema").path("\$ref").asString()).isEqualTo("#/components/schemas/LoginRequest")
        listOf("/api/admin/auth/login", "/api/admin/auth/refresh").forEach { path ->
            assertThat(docs.path("paths").path(path).path("post").path("responses").path("200").path("content")
                .path("application/json").path("schema").path("\$ref").asString())
                .describedAs(path).isEqualTo("#/components/schemas/AdminResponse")
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "LoginRequest, login|password",
        "AdminResponse, createdAt|id|login",
        "AdminListResponse, admins",
    )
    fun `dto fields are required`(schema: String, fields: String) {
        val required = apiDocs().path("components").path("schemas").path(schema).path("required")

        assertThat((0 until required.size()).map { required.get(it).asString() })
            .containsExactlyInAnyOrder(*fields.split('|').toTypedArray())
    }

    /**
     * Имена тегов — латиница kebab-case (из них Orval в режиме tags-split строит имена файлов), русское название —
     * в описании. У каждой операции тег объявлен, одноимённых тегов нет.
     */
    @Test
    fun `tags are latin kebab-case with russian descriptions`() {
        val docs = apiDocs()
        val tags = docs.path("tags")
        val names = (0 until tags.size()).map { tags.get(it).path("name").asString() }

        assertThat(names).containsExactlyInAnyOrder("auth", "admins")
        (0 until tags.size()).map(tags::get).forEach { tag ->
            assertThat(tag.path("name").asString()).matches(TAG_NAME)
            assertThat(tag.path("description").asString()).describedAs(tag.path("name").asString()).containsPattern("\\p{IsCyrillic}")
        }
        docs.path("paths").properties().forEach { (path, operations) ->
            operations.properties().forEach { (method, operation) ->
                val operationTags = operation.path("tags")
                assertThat((0 until operationTags.size()).map { operationTags.get(it).asString() })
                    .describedAs("$method $path").isNotEmpty().allMatch { it in names }
            }
        }
    }

    private companion object {
        const val TAG_NAME = "[a-z]+(-[a-z]+)*"
    }

    private fun apiDocs(): JsonNode =
        jsonMapper.readTree(mockMvc.get("/v3/api-docs/admin").andExpect { status { isOk() } }.andReturn().response.contentAsString)
}
