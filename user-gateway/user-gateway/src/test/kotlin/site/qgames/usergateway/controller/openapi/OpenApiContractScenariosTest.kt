package site.qgames.usergateway.controller.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.web.servlet.get
import site.qgames.usergateway.BaseIntegrationTest
import tools.jackson.databind.JsonNode

/**
 * Полиморфные DTO в OpenAPI — чистый union для генератора клиентов фронта (Orval): у родителя `oneOf` + `discriminator`,
 * вариант — обычный объект с литералом дискриминатора и обязательными полями, без обратной ссылки на родителя.
 */
class OpenApiContractScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "GameSettingsDto, NothingSettingsDto, rounds",
        "GameSettingsLimitsDto, NothingSettingsLimitsDto, rounds",
    )
    fun `polymorphic dto is a discriminated union`(union: String, variant: String, variantField: String) {
        val schemas = apiDocs().path("components").path("schemas")

        val unionSchema = schemas.path(union)
        assertThat(strings(unionSchema.path("oneOf")) { it.path("\$ref") }).containsExactly("#/components/schemas/$variant")
        assertThat(unionSchema.path("discriminator").path("propertyName").asString()).isEqualTo("gameType")
        assertThat(unionSchema.path("discriminator").path("mapping").path("NOTHING").asString())
            .isEqualTo("#/components/schemas/$variant")
        assertThat(unionSchema.has("properties")).isFalse()

        val variantSchema = schemas.path(variant)
        assertThat(variantSchema.has("allOf")).isFalse()
        assertThat(strings(variantSchema.path("properties").path("gameType").path("enum"))).containsExactly("NOTHING")
        assertThat(strings(variantSchema.path("required"))).contains("gameType", variantField)
    }

    private fun strings(array: JsonNode, item: (JsonNode) -> JsonNode = { it }): List<String> =
        (0 until array.size()).map { item(array.get(it)).asString() }

    private fun apiDocs(): JsonNode =
        jsonMapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.contentAsString)
}
