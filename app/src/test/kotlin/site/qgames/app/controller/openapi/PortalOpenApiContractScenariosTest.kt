package site.qgames.app.controller.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.web.servlet.get
import site.qgames.BaseIntegrationTest
import tools.jackson.databind.JsonNode

/**
 * OpenAPI группы portal (`/v3/api-docs/portal`) — вход генератора клиента портала (Orval).
 * Полиморфные DTO в OpenAPI — чистый union для генератора клиентов фронта (Orval): у родителя `oneOf` + `discriminator`,
 * вариант — обычный объект с литералом дискриминатора и обязательными полями, без обратной ссылки на родителя.
 */
class PortalOpenApiContractScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
        "GameSimpleSettingsDto, NothingSimpleSettingsDto, nothing, time",
        "GameSimpleSettingsDto, CheckersSimpleSettingsDto, checkers, variant",
        "GameAdvancedSettingsDto, NothingAdvancedSettingsDto, nothing, timeControl",
        "GameAdvancedSettingsDto, CheckersAdvancedSettingsDto, checkers, removeCapturedAfterMove",
        "GameSettingsLimitsDto, NothingSettingsLimitsDto, nothing, rounds",
        "GameSettingsLimitsDto, CheckersSettingsLimitsDto, checkers, variants",
    )
    fun `polymorphic dto is a discriminated union`(union: String, variant: String, gameCode: String, variantField: String) {
        val schemas = apiDocs().path("components").path("schemas")

        val unionSchema = schemas.path(union)
        assertThat(strings(unionSchema.path("oneOf")) { it.path("\$ref") }).hasSize(2).contains("#/components/schemas/$variant")
        assertThat(unionSchema.path("discriminator").path("propertyName").asString()).isEqualTo("gameCode")
        assertThat(unionSchema.path("discriminator").path("mapping").path(gameCode).asString())
            .isEqualTo("#/components/schemas/$variant")
        assertThat(unionSchema.has("properties")).isFalse()

        val variantSchema = schemas.path(variant)
        assertThat(variantSchema.has("allOf")).isFalse()
        assertThat(strings(variantSchema.path("properties").path("gameCode").path("enum"))).containsExactly(gameCode)
        assertThat(strings(variantSchema.path("required"))).contains("gameCode", variantField)
    }

    /**
     * Варианты правил шашек: все три в контракте, доступные — в границах настроек. В advanced варианта нет — доска, первый
     * ход и правила ничьих задаются напрямую, а вариант остаётся пресетом в границах.
     */
    @Test
    fun `checkers settings list all variants`() {
        val schemas = apiDocs().path("components").path("schemas")

        assertThat(strings(schemas.path("CheckersSimpleSettingsDto").path("properties").path("variant").path("enum")))
            .containsExactly("RUSSIAN", "INTERNATIONAL", "ENGLISH")
        assertThat(strings(schemas.path("CheckersSimpleSettingsDto").path("properties").path("time").path("enum")))
            .containsExactly("NONE", "CLASSIC", "RAPID", "BLITZ")
        val advanced = schemas.path("CheckersAdvancedSettingsDto")
        assertThat(advanced.path("properties").has("variant")).isFalse()
        assertThat(strings(advanced.path("required"))).contains("boardSize", "firstColor", "endgameRule", "drawQuietMoves")
        assertThat(strings(advanced.path("properties").path("endgameRule").path("enum"))).containsExactly("NONE", "RUSSIAN", "INTERNATIONAL")
        assertThat(strings(schemas.path("CheckersSettingsLimitsDto").path("properties").path("variants").path("items").path("enum")))
            .containsExactly("RUSSIAN", "INTERNATIONAL", "ENGLISH")
        assertThat(strings(schemas.path("CheckersSettingsLimitsDto").path("required"))).contains("boardSizes", "drawQuietMoves", "rulePresets")
        assertThat(strings(schemas.path("CheckersRulePresetDto").path("required")))
            .contains("variant", "boardSize", "firstColor", "endgameRule", "drawQuietMoves", "removeCapturedAfterMove")
    }

    /** Код игры — строчные коды (`@JsonValue`), а не имена значений enum. */
    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource(
        "RoomResponse, gameCode, nothing|carcassonne|checkers",
        "GameSummaryDto, code, nothing|carcassonne|checkers",
        "GameDetailsResponse, code, nothing|carcassonne|checkers",
    )
    fun `game enums expose their public values`(schema: String, property: String, values: String) {
        val node = apiDocs().path("components").path("schemas").path(schema).path("properties").path(property)

        assertThat(strings(node.path("enum"))).containsExactly(*values.split('|').toTypedArray())
    }

    @Test
    fun `game rules are required and game code in the path lists all games`() {
        val docs = apiDocs()

        val details = docs.path("components").path("schemas").path("GameDetailsResponse")
        assertThat(strings(details.path("required"))).contains("code", "title", "description", "rules")
        // Статус публикации наружу не выходит: в каталоге и по ссылке — только доступные игры.
        assertThat(details.path("properties").has("publishStatus")).isFalse()
        assertThat(docs.path("components").path("schemas").path("GameSummaryDto").path("properties").has("publishStatus")).isFalse()
        assertThat(docs.path("components").path("schemas").has("GamePublishStatus")).isFalse()
        listOf(
            "/api/games/{gameCode}" to "get",
            "/api/games/{gameCode}/rooms" to "post",
            "/api/games/{gameCode}/rooms/{roomId}/settings" to "put",
            "/api/games/{gameCode}/rooms/{roomId}/moves" to "post",
            "/api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events" to "get",
        ).forEach { (path, method) ->
            val parameters = docs.path("paths").path(path).path(method).path("parameters")
            val parameter = (0 until parameters.size()).map(parameters::get).single { it.path("name").asString() == "gameCode" }
            assertThat(strings(parameter.path("schema").path("enum"))).describedAs(path).containsExactly("nothing", "carcassonne", "checkers")
        }
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
        assertThat(operation.path("responses").propertyNames().toList()).containsExactly("200", "401", "404", "409")

        val schemas = docs.path("components").path("schemas")
        val union = schemas.path("RoomStreamEvent")
        assertThat(union.path("discriminator").path("propertyName").asString()).isEqualTo("type")
        assertThat(union.has("properties")).isFalse()
        assertThat(strings(union.path("oneOf")) { it.path("\$ref") })
            .containsExactly("#/components/schemas/RoomSnapshotEvent", "#/components/schemas/RoomPatchStreamEvent")
        val variants = mapOf(
            "RoomSnapshotEvent" to (listOf("snapshot") to "RoomSnapshotDto"),
            "RoomPatchStreamEvent" to (listOf("put", "patch", "delete", "info") to "RoomPatchEvent"),
        )
        variants.forEach { (variant, typesAndData) ->
            val (types, data) = typesAndData
            types.forEach { type ->
                assertThat(union.path("discriminator").path("mapping").path(type).asString()).isEqualTo("#/components/schemas/$variant")
            }
            val schema = schemas.path(variant)
            assertThat(schema.has("allOf")).isFalse()
            assertThat(strings(schema.path("properties").path("type").path("enum"))).containsExactlyInAnyOrderElementsOf(types)
            assertThat(strings(schema.path("required"))).contains("type", "data")
            assertThat(schema.path("properties").path("data").path("\$ref").asString()).isEqualTo("#/components/schemas/$data")
        }
        assertThat(strings(schemas.path("RoomSnapshotDto").path("required"))).containsExactlyInAnyOrder("seq", "room")
        val patch = schemas.path("RoomPatchEvent")
        assertThat(patch.path("discriminator").path("propertyName").asString()).isEqualTo("method")
        assertThat(strings(patch.path("oneOf")) { it.path("\$ref") }).containsExactly(
            "#/components/schemas/RoomEventPut",
            "#/components/schemas/RoomEventPatch",
            "#/components/schemas/RoomEventDelete",
            "#/components/schemas/RoomEventInfo",
        )
        assertThat(patch.path("description").asString()).contains("member-joined", "owner-changed", "session-ended", "room-deleted")
        assertThat(strings(schemas.path("RoomMemberResponse").path("required"))).contains("id").doesNotContain("userId", "owner")
        assertThat(schemas.propertyNames().toList()).doesNotContain(
            "RoomUsersChangedEvent", "RoomSettingsChangedEvent", "RoomSessionChangedEvent", "RoomDeletedEvent", "EmptyEventDataDto",
        )
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
        assertThat(operation.path("responses").propertyNames().toList()).containsExactly("200", "401", "403", "404", "409")

        val schemas = docs.path("components").path("schemas")
        val union = schemas.path("SessionStreamEvent")
        assertThat(union.path("discriminator").path("propertyName").asString()).isEqualTo("type")
        assertThat(union.has("properties")).isFalse()
        assertThat(strings(union.path("oneOf")) { it.path("\$ref") })
            .containsExactly("#/components/schemas/SessionSnapshotEvent", "#/components/schemas/SessionPatchStreamEvent")
        val variants = mapOf(
            "SessionSnapshotEvent" to (listOf("snapshot") to "SessionSnapshotDto"),
            "SessionPatchStreamEvent" to (listOf("put", "patch", "delete", "info") to "SessionPatchEvent"),
        )
        variants.forEach { (variant, typesAndData) ->
            val (types, data) = typesAndData
            types.forEach { type ->
                assertThat(union.path("discriminator").path("mapping").path(type).asString()).isEqualTo("#/components/schemas/$variant")
            }
            val schema = schemas.path(variant)
            assertThat(schema.has("allOf")).isFalse()
            assertThat(strings(schema.path("properties").path("type").path("enum"))).containsExactlyInAnyOrderElementsOf(types)
            assertThat(strings(schema.path("required"))).contains("type", "data")
            assertThat(schema.path("properties").path("data").path("\$ref").asString()).isEqualTo("#/components/schemas/$data")
        }
        assertThat(strings(schemas.path("SessionSnapshotDto").path("required")))
            .containsExactlyInAnyOrder("seq", "status", "serverTime", "meta", "state")
        assertThat(strings(schemas.path("SessionTimerDto").path("required"))).containsExactlyInAnyOrder("status", "clocks")
        assertThat(strings(schemas.path("SessionTimerDto").path("properties").path("status").path("enum")))
            .containsExactly("RUNNING", "PAUSED", "STOPPED")
        assertThat(strings(schemas.path("SessionPlayerDto").path("required"))).containsExactlyInAnyOrder("id", "moveOptions")
        // Типизированных событий больше нет: события — обобщённые патчи документа.
        assertThat(schemas.propertyNames().toList()).doesNotContain(
            "SessionStartedEvent", "SessionStartedFrame", "SessionEndedFrame", "SessionPlayerChangedEvent", "SessionTurnsChangedEvent",
            "SessionTimerChangedEvent", "SessionMovedEvent", "SessionMovedDto", "NothingMovedDto",
        )
    }

    /** Событие партии — union патчей по method: {seq, at, method, code, data}, data — по методу. */
    @Test
    fun `session patch event is a union by method`() {
        val schemas = apiDocs().path("components").path("schemas")

        val union = schemas.path("SessionPatchEvent")
        assertThat(union.path("discriminator").path("propertyName").asString()).isEqualTo("method")
        assertThat(union.has("properties")).isFalse()
        assertThat(union.path("description").asString())
            .contains("session-started", "move-options-changed", "game-paused", "result-set", "piece-moved", "player-resigned")
        val variants = mapOf(
            "PUT" to ("SessionEventPut" to "PutData"),
            "PATCH" to ("SessionEventPatch" to "PatchData"),
            "DELETE" to ("SessionEventDelete" to "DeleteData"),
            "INFO" to ("SessionEventInfo" to "InfoData"),
        )
        assertThat(strings(union.path("oneOf")) { it.path("\$ref") })
            .containsExactlyElementsOf(variants.values.map { "#/components/schemas/${it.first}" })
        variants.forEach { (method, names) ->
            val (variant, data) = names
            assertThat(union.path("discriminator").path("mapping").path(method).asString()).isEqualTo("#/components/schemas/$variant")
            val schema = schemas.path(variant)
            assertThat(schema.has("allOf")).isFalse()
            assertThat(strings(schema.path("properties").path("method").path("enum"))).containsExactly(method)
            assertThat(schema.path("properties").path("code").path("type").asString()).isEqualTo("string")
            assertThat(schema.path("properties").path("code").has("enum")).isFalse()
            assertThat(strings(schema.path("required"))).containsExactlyInAnyOrder("seq", "at", "method", "code", "data")
            assertThat(schema.path("properties").path("data").path("\$ref").asString()).isEqualTo("#/components/schemas/$data")
        }

        // Путь — массив сегментов: строка (ключ) или {id} (элемент массива).
        listOf("PutData", "PatchData", "DeleteData").forEach { data ->
            assertThat(schemas.path(data).path("properties").path("path").path("items").path("\$ref").asString())
                .isEqualTo("#/components/schemas/PathSegment")
            assertThat(strings(schemas.path(data).path("required"))).contains("path")
        }
        val segment = schemas.path("PathSegment").path("oneOf")
        assertThat(segment.get(0).path("type").asString()).isEqualTo("string")
        assertThat(segment.get(1).path("\$ref").asString()).isEqualTo("#/components/schemas/PathSegmentId")
        assertThat(strings(schemas.path("PathSegmentId").path("required"))).containsExactly("id")
        // value PUT — любой JSON (схемы нет, в том числе null), но ключ есть всегда — required; value PATCH и данные INFO — объект.
        assertThat(strings(schemas.path("PutData").path("required"))).containsExactlyInAnyOrder("path", "value")
        val putValue = schemas.path("PutData").path("properties").path("value")
        assertThat(putValue.has("type")).isFalse()
        assertThat(putValue.has("\$ref")).isFalse()
        assertThat(schemas.path("PatchData").path("properties").path("value").path("type").asString()).isEqualTo("object")
        assertThat(strings(schemas.path("PatchData").path("required"))).containsExactlyInAnyOrder("path", "value")
        assertThat(schemas.path("DeleteData").path("properties").propertyNames().toList()).containsExactly("path")
        assertThat(schemas.path("InfoData").path("type").asString()).isEqualTo("object")
        assertThat(schemas.path("InfoData").path("additionalProperties").asBoolean()).isTrue()
    }

    /** Состояние — union схем конкретных игр (игра — meta.game.code), без обратных ссылок. */
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        "SessionStateDto, NothingSessionStateDto, players|timer|turns|result|moves, players|turns|moves",
        "SessionStateDto, CheckersSessionStateDto, " +
            "players|timer|turns|result|pieces|lastMove|quietMoves|materialMoves|positionHistory|drawOfferedBy, " +
            "players|turns|pieces|quietMoves|materialMoves|positionHistory",
        "SessionAdvancedRulesDto, CheckersAdvancedRulesDto, " +
            "boardSize|firstColor|menMoveBackward|menCaptureBackward|flyingKings|captureRequired|maxCaptureRequired|" +
            "promoteMidCapture|removeCapturedAfterMove, " +
            "boardSize|firstColor|menMoveBackward|menCaptureBackward|flyingKings|captureRequired|maxCaptureRequired|" +
            "promoteMidCapture|removeCapturedAfterMove",
    )
    fun `game specific session state is a union of game schemas`(union: String, variant: String, properties: String, required: String) {
        val schemas = apiDocs().path("components").path("schemas")

        assertThat(strings(schemas.path(union).path("oneOf")) { it.path("\$ref") }).contains("#/components/schemas/$variant")
        assertThat(schemas.path(union).has("discriminator")).isFalse()
        val schema = schemas.path(variant)
        assertThat(schema.has("allOf")).isFalse()
        assertThat(schema.path("properties").propertyNames().toList()).containsExactlyInAnyOrder(*properties.split('|').toTypedArray())
        assertThat(strings(schema.path("required"))).containsExactlyInAnyOrder(*required.split('|').toTypedArray())
    }

    /** Платформа партии: итог (блок core result), итоговые правила в meta, поля игры у игрока, схема параметров хода шашек. */
    @Test
    fun `session result, advanced rules and checkers schemas are in the contract`() {
        val schemas = apiDocs().path("components").path("schemas")

        assertThat(strings(schemas.path("SessionResultDto").path("required"))).containsExactlyInAnyOrder("outcome", "winnerIds", "reason")
        assertThat(strings(schemas.path("SessionResultDto").path("properties").path("outcome").path("enum"))).containsExactly("WIN", "DRAW")
        assertThat(schemas.path("SessionMetaDto").path("properties").propertyNames().toList()).containsExactlyInAnyOrder("game", "advancedRules")
        assertThat(strings(schemas.path("SessionMetaDto").path("required"))).containsExactly("game")
        assertThat(strings(schemas.path("CheckersPlayerDto").path("required"))).containsExactlyInAnyOrder("id", "moveOptions", "color")
        assertThat(strings(schemas.path("CheckersPlayerDto").path("properties").path("color").path("enum"))).containsExactly("WHITE", "BLACK")
        assertThat(strings(schemas.path("CheckersPieceDto").path("properties").path("kind").path("enum"))).containsExactly("MAN", "KING")
        assertThat(strings(schemas.path("CheckersPieceDto").path("required"))).containsExactlyInAnyOrder("id", "color", "kind", "square")
        val square = schemas.path("CheckersPieceDto").path("properties").path("square")
        assertThat(square.path("minItems").asInt()).isEqualTo(2)
        assertThat(square.path("maxItems").asInt()).isEqualTo(2)
        assertThat(strings(schemas.path("CheckersLastMoveDto").path("required"))).containsExactlyInAnyOrder("pieceId", "path", "captured")
        // Параметры хода шашек — в components, общий конверт хода не меняется.
        assertThat(strings(schemas.path("CheckersMoveDataDto").path("required"))).containsExactly("path")
        assertThat(schemas.path("CheckersMoveDataDto").path("properties").path("path").path("items").path("items").path("type").asString())
            .isEqualTo("integer")
        val data = schemas.path("MoveRequest").path("properties").path("data")
        assertThat(strings(data.path("type"))).containsExactlyInAnyOrder("object", "null")
        assertThat(data.has("\$ref")).isFalse()
        assertThat(data.path("description").asString()).contains("CheckersMoveDataDto")
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
            "/api/rooms/{roomId}/session" to "post",
            "/api/rooms/{roomId}/role" to "put",
            "/api/rooms/{roomId}/owner" to "put",
            "/api/games/{gameCode}/rooms/{roomId}/settings" to "put",
        ).forEach { (path, method) ->
            val responses = paths.path(path).path(method).path("responses")
            assertThat(responses.has("204")).describedAs("$method $path").isTrue()
            assertThat(responses.has("200")).describedAs("$method $path").isFalse()
            assertThat(responses.path("204").has("content")).describedAs("$method $path").isFalse()
        }
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

    /** Ручки auth и users отдают DTO user-auth, но токены остаются в куках: внутренние DTO сессии наружу не выходят. */
    @Test
    fun `auth tokens are not part of the public contract`() {
        val docs = apiDocs()

        assertThat(docs.path("components").path("schemas").propertyNames().toList())
            .contains("UserResponse", "LoginRequest")
            .doesNotContain("AuthResponse", "TokenPair", "RefreshTokenRequest")
        listOf("/api/auth/refresh", "/api/auth/logout").forEach { path ->
            assertThat(docs.path("paths").path(path).path("post").has("requestBody")).describedAs(path).isFalse()
        }
    }

    /**
     * Регистрация только через внешнего провайдера (Google) с выбором логина: регистрации и подтверждения по email нет,
     * email в профиле нет, есть неизменяемый логин.
     */
    @Test
    fun `registration goes only through google and email is gone`() {
        val docs = apiDocs()

        assertThat(docs.path("paths").propertyNames().toList())
            .contains("/api/auth/login", "/api/auth/google/login", "/api/auth/google/register")
            .doesNotContain("/api/auth/google", "/api/auth/register", "/api/auth/verify-email", "/api/auth/resend-code")
        val userProperties = docs.path("components").path("schemas").path("UserResponse").path("properties")
        assertThat(userProperties.has("login")).isTrue()
        assertThat(userProperties.has("email")).isFalse()
        assertThat(docs.path("components").path("schemas").path("LoginRequest").path("properties").has("email")).isFalse()
        assertThat(docs.path("components").path("schemas").propertyNames().toList())
            .doesNotContain("RegisterRequest", "RegistrationResponse", "VerifyEmailRequest", "ResendCodeRequest")
        assertThat(docs.path("components").path("schemas").path("UserResponse").path("properties").has("emailVerified")).isFalse()
        assertThat(docs.path("paths").path("/api/auth/login").path("post").path("responses").has("403")).isFalse()
        assertThat(docs.toString()).doesNotContain(
            "(EMAIL_NOT_VERIFIED)", "GOOGLE_EMAIL_NOT_VERIFIED", "ACCOUNT_LINK_REQUIRED", "EMAIL_ALREADY_REGISTERED",
            "EMAIL_ALREADY_VERIFIED", "INVALID_VERIFICATION_CODE",
            "VERIFICATION_CODE_EXPIRED", "TOO_MANY_VERIFICATION_ATTEMPTS", "RESEND_COOLDOWN",
        )
    }

    /**
     * Имена тегов — латиница kebab-case (из них Orval в режиме tags-split строит имена файлов), русское название —
     * в описании. Теги отсортированы по имени (стабильный клиент Orval). У каждой операции тег объявлен, одноимённых
     * тегов нет.
     */
    @Test
    fun `tags are latin kebab-case with russian descriptions`() {
        val docs = apiDocs()
        val tags = docs.path("tags")
        val names = (0 until tags.size()).map { tags.get(it).path("name").asString() }

        assertThat(names).containsExactly("auth", "games", "rooms", "users")
        (0 until tags.size()).map(tags::get).forEach { tag ->
            assertThat(tag.path("name").asString()).matches(TAG_NAME)
            assertThat(tag.path("description").asString()).describedAs(tag.path("name").asString()).containsPattern("\\p{IsCyrillic}")
        }
        docs.path("paths").properties().forEach { (path, operations) ->
            operations.properties().forEach { (method, operation) ->
                assertThat(strings(operation.path("tags"))).describedAs("$method $path").isNotEmpty().allMatch { it in names }
            }
        }
    }

    /** Ручки админки — в своей группе; downstream-сервисов нет, поэтому 503 SERVICE_UNAVAILABLE не описывается. */
    @Test
    fun `portal group has no admin endpoints and no service unavailable responses`() {
        val docs = apiDocs()

        assertThat(docs.path("paths").propertyNames().toList()).allMatch { it.startsWith("/api/") && !it.startsWith("/api/admin") }
        assertThat(docs.path("components").path("schemas").has("AdminResponse")).isFalse()
        docs.path("paths").properties().forEach { (path, operations) ->
            operations.properties().forEach { (method, operation) ->
                assertThat(operation.path("responses").has("503")).describedAs("$method $path").isFalse()
            }
        }
        assertThat(docs.toString()).doesNotContain("SERVICE_UNAVAILABLE", "X-User-Id", "gateway")
    }

    /** Сгенерированный клиент на фронте не должен меняться без изменения контракта (springdoc.writer-with-order-by-keys). */
    @ParameterizedTest(name = "{0}")
    @CsvSource("paths", "components.schemas")
    fun `openapi keys are sorted`(section: String) {
        val node = section.split('.').fold(apiDocs()) { node, key -> node.path(key) }

        val keys = node.propertyNames().toList()

        assertThat(keys).isNotEmpty().isSorted()
    }

    private companion object {
        const val TAG_NAME = "[a-z]+(-[a-z]+)*"
    }

    private fun strings(array: JsonNode, item: (JsonNode) -> JsonNode = { it }): List<String> =
        (0 until array.size()).map { item(array.get(it)).asString() }

    private fun apiDocs(): JsonNode =
        jsonMapper.readTree(mockMvc.get("/v3/api-docs/portal").andExpect { status { isOk() } }.andReturn().response.contentAsString)
}
