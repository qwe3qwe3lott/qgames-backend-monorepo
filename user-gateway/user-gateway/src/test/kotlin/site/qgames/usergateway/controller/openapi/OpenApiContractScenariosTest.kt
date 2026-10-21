package site.qgames.usergateway.controller.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        assertThat(unionSchema.path("discriminator").path("propertyName").asString()).isEqualTo("gameCode")
        assertThat(unionSchema.path("discriminator").path("mapping").path("nothing").asString())
            .isEqualTo("#/components/schemas/$variant")
        assertThat(unionSchema.has("properties")).isFalse()

        val variantSchema = schemas.path(variant)
        assertThat(variantSchema.has("allOf")).isFalse()
        assertThat(strings(variantSchema.path("properties").path("gameCode").path("enum"))).containsExactly("nothing")
        assertThat(strings(variantSchema.path("required"))).contains("gameCode", variantField)
    }

    /** Код игры — строчные коды (`@JsonValue`), а не имена значений enum; NOT_PUBLISHED и FORCE_DISABLED наружу не выходят. */
    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource(
        "RoomResponse, gameCode, nothing|carcassonne",
        "GameSummaryDto, code, nothing|carcassonne",
        "GameDetailsResponse, code, nothing|carcassonne",
        "GameSummaryDto, publishStatus, PUBLISHED|DISABLED|HIDDEN",
        "GameDetailsResponse, publishStatus, PUBLISHED|DISABLED|HIDDEN",
    )
    fun `game enums expose their public values`(schema: String, property: String, values: String) {
        val node = apiDocs().path("components").path("schemas").path(schema).path("properties").path(property)

        assertThat(strings(node.path("enum"))).containsExactly(*values.split('|').toTypedArray())
    }

    @Test
    fun `game rules are required and game code in the path lists all games`() {
        val docs = apiDocs()

        val details = docs.path("components").path("schemas").path("GameDetailsResponse")
        assertThat(strings(details.path("required"))).contains("code", "title", "description", "publishStatus", "rules")
        val parameter = docs.path("paths").path("/api/games/{gameCode}").path("get").path("parameters").get(0)
        assertThat(strings(parameter.path("schema").path("enum"))).containsExactly("nothing", "carcassonne")
    }

    /** Поток событий комнаты: text/event-stream со схемой-union событий, конверты {type, data} в components. */
    @Test
    fun `room events stream is described as a union of events`() {
        val docs = apiDocs()

        val operation = docs.path("paths").path("/api/rooms/{roomId}/events").path("get")
        val ok = operation.path("responses").path("200").path("content")
        assertThat(ok.propertyNames().toList()).containsExactly("text/event-stream")
        assertThat(ok.path("text/event-stream").path("schema").path("\$ref").asString())
            .isEqualTo("#/components/schemas/RoomStreamEvent")
        assertThat(operation.path("responses").propertyNames().toList()).containsExactly("200", "401", "404", "503")

        val schemas = docs.path("components").path("schemas")
        val union = schemas.path("RoomStreamEvent")
        assertThat(union.path("discriminator").path("propertyName").asString()).isEqualTo("type")
        assertThat(union.has("properties")).isFalse()
        val variants = mapOf(
            "snapshot" to "RoomSnapshotEvent",
            "users-changed" to "RoomUsersChangedEvent",
            "settings-changed" to "RoomSettingsChangedEvent",
            "session-changed" to "RoomSessionChangedEvent",
            "room-deleted" to "RoomDeletedEvent",
        )
        assertThat(strings(union.path("oneOf")) { it.path("\$ref") })
            .containsExactlyInAnyOrderElementsOf(variants.values.map { "#/components/schemas/$it" })
        variants.forEach { (type, variant) ->
            assertThat(union.path("discriminator").path("mapping").path(type).asString()).isEqualTo("#/components/schemas/$variant")
            val schema = schemas.path(variant)
            assertThat(schema.has("allOf")).isFalse()
            assertThat(strings(schema.path("properties").path("type").path("enum"))).containsExactly(type)
            assertThat(strings(schema.path("required"))).contains("type", "data")
        }
        assertThat(schemas.path("RoomSnapshotEvent").path("properties").path("data").path("\$ref").asString())
            .isEqualTo("#/components/schemas/RoomResponse")
        assertThat(schemas.path("EmptyEventDataDto").path("type").asString()).isEqualTo("object")
        assertThat(schemas.has("ResponseBodyEmitter")).isFalse()
    }

    /** Поток партии: text/event-stream со схемой-union событий; данные игры — схемы конкретной игры в components. */
    @Test
    fun `session events stream is described as a union of events`() {
        val docs = apiDocs()

        val operation = docs.path("paths").path("/api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events").path("get")
        val ok = operation.path("responses").path("200").path("content")
        assertThat(ok.propertyNames().toList()).containsExactly("text/event-stream")
        assertThat(ok.path("text/event-stream").path("schema").path("\$ref").asString())
            .isEqualTo("#/components/schemas/SessionStreamEvent")
        assertThat(operation.path("responses").propertyNames().toList()).containsExactly("200", "401", "403", "404", "409", "503")

        val schemas = docs.path("components").path("schemas")
        val union = schemas.path("SessionStreamEvent")
        assertThat(union.path("discriminator").path("propertyName").asString()).isEqualTo("type")
        assertThat(union.has("properties")).isFalse()
        val variants = mapOf(
            "snapshot" to ("SessionSnapshotEvent" to "SessionSnapshotDto"),
            "started" to ("SessionStartedEvent" to "SessionStartedFrame"),
            "session-finished" to ("SessionFinishedEvent" to "SessionEndedFrame"),
            "session-cancelled" to ("SessionCancelledEvent" to "SessionEndedFrame"),
            "player-changed" to ("SessionPlayerChangedEvent" to "SessionPlayerChangedFrame"),
            "turns-changed" to ("SessionTurnsChangedEvent" to "SessionTurnsChangedFrame"),
            "timer-changed" to ("SessionTimerChangedEvent" to "SessionTimerChangedFrame"),
            "moved" to ("SessionMovedEvent" to "SessionMovedFrame"),
        )
        assertThat(strings(union.path("oneOf")) { it.path("\$ref") })
            .containsExactlyInAnyOrderElementsOf(variants.values.map { "#/components/schemas/${it.first}" })
        variants.forEach { (type, names) ->
            val (variant, data) = names
            assertThat(union.path("discriminator").path("mapping").path(type).asString()).isEqualTo("#/components/schemas/$variant")
            val schema = schemas.path(variant)
            assertThat(schema.has("allOf")).isFalse()
            assertThat(strings(schema.path("properties").path("type").path("enum"))).containsExactly(type)
            assertThat(strings(schema.path("required"))).contains("type", "data")
            assertThat(schema.path("properties").path("data").path("\$ref").asString()).isEqualTo("#/components/schemas/$data")
        }
        listOf("SessionStartedFrame", "SessionEndedFrame", "SessionPlayerChangedFrame", "SessionTurnsChangedFrame",
            "SessionTimerChangedFrame", "SessionMovedFrame").forEach { frame ->
            assertThat(strings(schemas.path(frame).path("required"))).containsExactlyInAnyOrder("seq", "at", "data")
        }
        assertThat(strings(schemas.path("SessionSnapshotDto").path("required")))
            .containsExactlyInAnyOrder("seq", "status", "serverTime", "meta", "state")
        assertThat(strings(schemas.path("SessionTimerDto").path("required"))).containsExactly("status")
        assertThat(strings(schemas.path("SessionTimerDto").path("properties").path("status").path("enum")))
            .containsExactly("RUNNING", "PAUSED", "STOPPED")
        assertThat(strings(schemas.path("SessionPlayerDto").path("required"))).containsExactlyInAnyOrder("id", "moveOptions")
    }

    /** Состояние и данные игровых событий — union схем конкретных игр (игра — meta.game.code), без обратных ссылок. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "SessionStateDto, NothingSessionStateDto, players|timer|turns|moves",
        "SessionMovedDto, NothingMovedDto, playerId|type|timeout",
    )
    fun `game specific session data is a union of game schemas`(union: String, variant: String, properties: String) {
        val schemas = apiDocs().path("components").path("schemas")

        assertThat(strings(schemas.path(union).path("oneOf")) { it.path("\$ref") }).containsExactly("#/components/schemas/$variant")
        val schema = schemas.path(variant)
        assertThat(schema.has("allOf")).isFalse()
        assertThat(schema.path("properties").propertyNames().toList()).containsExactlyInAnyOrder(*properties.split('|').toTypedArray())
    }

    @Test
    fun `session mutations answer 204 and the session in the room is brief`() {
        val docs = apiDocs()
        val paths = docs.path("paths")

        listOf(
            "/api/games/{gameCode}/rooms/{roomId}/moves" to "post",
            "/api/rooms/{roomId}/session/pause" to "post",
            "/api/rooms/{roomId}/session/resume" to "post",
            "/api/rooms/{roomId}/session" to "delete",
        ).forEach { (path, method) ->
            val responses = paths.path(path).path(method).path("responses")
            assertThat(responses.has("204")).describedAs("$method $path").isTrue()
            assertThat(responses.has("200")).describedAs("$method $path").isFalse()
            assertThat(responses.path("204").has("content")).describedAs("$method $path").isFalse()
        }
        assertThat(paths.path("/api/rooms/{roomId}/session").path("post").path("responses").path("200").path("content")
            .path("application/json").path("schema").path("\$ref").asString()).isEqualTo("#/components/schemas/RoomResponse")
        val schemas = docs.path("components").path("schemas")
        assertThat(schemas.path("SessionResponse").path("properties").propertyNames().toList()).containsExactlyInAnyOrder("id", "status")
        assertThat(schemas.path("MoveRequest").path("properties").propertyNames().toList()).containsExactlyInAnyOrder("type", "data")
        assertThat(docs.toString()).doesNotContain("SESSION_CONFLICT", "expectedVersion")
    }

    @Test
    fun `presence is the stream, join and leave are gone`() {
        val paths = apiDocs().path("paths").propertyNames().toList()

        assertThat(paths).contains("/api/rooms/{roomId}/events")
        assertThat(paths).doesNotContain("/api/rooms/{roomId}/join", "/api/rooms/{roomId}/leave")
    }

    /** Снапшот API на фронте не должен меняться без изменения контракта (springdoc.writer-with-order-by-keys). */
    @ParameterizedTest(name = "{0}")
    @CsvSource("paths", "components.schemas")
    fun `openapi keys are sorted`(section: String) {
        val node = section.split('.').fold(apiDocs()) { node, key -> node.path(key) }

        val keys = node.propertyNames().toList()

        assertThat(keys).isNotEmpty().isSorted()
    }

    private fun strings(array: JsonNode, item: (JsonNode) -> JsonNode = { it }): List<String> =
        (0 until array.size()).map { item(array.get(it)).asString() }

    private fun apiDocs(): JsonNode =
        jsonMapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.contentAsString)
}
