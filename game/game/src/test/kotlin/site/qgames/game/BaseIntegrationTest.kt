package site.qgames.game

import com.ninjasquad.springmockk.MockkSpyBean
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import site.qgames.common.test.container.PostgresTestContainer
import site.qgames.common.test.container.RedpandaTestContainer
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.GameCodes
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.job.room.RoomLeaveJob
import site.qgames.games.nothing.NothingGameRules
import site.qgames.userauth.asyncapi.event.Avatar
import site.qgames.userauth.asyncapi.event.UserProfileChanged
import site.qgames.userauth.asyncapi.topic.UserAuthTopics
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * База всех тестов game: полный контекст, реальные PostgreSQL и Redpanda в контейнерах.
 * Перед каждым тестом init.sql пересоздаёт данные (очистка + реплика user_auth."user" из [site.qgames.game.support.TestData]).
 * Запросы идут через MockMvc в настоящие контроллеры, CDC-события — настоящим Kafka-продюсером в топик.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/init.sql")
abstract class BaseIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jsonMapper: JsonMapper

    @Autowired
    protected lateinit var jdbcClient: JdbcClient

    @Autowired
    protected lateinit var roomLeaveJob: RoomLeaveJob

    /** Правила «игры в ничто»: шпион, чтобы проверять ветки, недостижимые с настоящими правилами (сброс — после теста). */
    @MockkSpyBean
    protected lateinit var nothingGameRules: NothingGameRules

    protected fun call(method: HttpMethod, path: String, userId: UUID?, body: Any? = null): ResultActionsDsl =
        mockMvc.request(method, path) {
            userId?.let { header(QgamesHeaders.USER_ID, it.toString()) }
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(it)
            }
        }

    protected final inline fun <reified T> ResultActionsDsl.body(): T =
        jsonMapper.readValue(andReturn().response.contentAsString, T::class.java)

    /** Настройки «игры в ничто»: по умолчанию — как в GameRules (3 круга, без таймера). */
    protected fun nothingSettings(
        rounds: Int = 3,
        mode: TimeControlMode = TimeControlMode.NONE,
        moveSeconds: Int? = null,
    ) = RoomSettingsDto(TimeControlDto(mode, moveSeconds), NothingSettingsDto(GameType.NOTHING, rounds))

    protected fun createRoomRequest(owner: UUID?, body: Any, gameCode: String = GameCodes.NOTHING): ResultActionsDsl =
        call(HttpMethod.POST, "/api/games/$gameCode/rooms", owner, body)

    protected fun createRoom(owner: UUID, settings: RoomSettingsDto = nothingSettings()): RoomResponse =
        createRoomRequest(owner, CreateRoomRequest(settings))
            .andExpect { status { isOk() } }
            .body()

    protected fun updateSettingsRequest(user: UUID, roomId: UUID, body: Any, gameCode: String = GameCodes.NOTHING): ResultActionsDsl =
        call(HttpMethod.PUT, "/api/games/$gameCode/rooms/$roomId/settings", user, body)

    protected fun room(roomId: UUID, viewer: UUID): RoomResponse =
        call(HttpMethod.GET, "/api/rooms/$roomId", viewer).andExpect { status { isOk() } }.body()

    protected fun join(user: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.POST, "/api/rooms/$roomId/join", user).andExpect { status { isOk() } }.body()

    protected fun leave(user: UUID, roomId: UUID) {
        call(HttpMethod.POST, "/api/rooms/$roomId/leave", user).andExpect { status { isOk() } }
    }

    /**
     * Выход, доведённый до конца: срок выхода сдвигается в прошлое и RoomLeaveJob удаляет участника — так, как будто
     * он не вернулся вовремя.
     */
    protected fun leaveCompletely(user: UUID, roomId: UUID) {
        leave(user, roomId)
        expireLeave(roomId, user)
        roomLeaveJob.run()
    }

    /** Сдвигает срок выхода участника в прошлое (по расписанию RoomLeaveJob в тестах выключен). */
    protected fun expireLeave(roomId: UUID, user: UUID) {
        jdbcClient.sql(
            "UPDATE room_member SET leave_deadline = now() - interval '1 second' WHERE room_id = :roomId AND user_id = :userId",
        )
            .param("roomId", roomId)
            .param("userId", user)
            .update()
    }

    protected fun leaveDeadline(roomId: UUID, user: UUID): Instant? =
        jdbcClient.sql("SELECT leave_deadline FROM room_member WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", user)
            .query { rs, _ -> rs.getTimestamp("leave_deadline")?.toInstant() }
            .list()
            .single()

    protected fun changeRole(user: UUID, roomId: UUID, role: RoomRole): RoomResponse =
        call(HttpMethod.PUT, "/api/rooms/$roomId/role", user, ChangeRoleRequest(role)).andExpect { status { isOk() } }.body()

    protected fun start(owner: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.POST, "/api/rooms/$roomId/session", owner).andExpect { status { isOk() } }.body()

    protected fun cancel(owner: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.DELETE, "/api/rooms/$roomId/session", owner).andExpect { status { isOk() } }.body()

    protected fun pause(owner: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.POST, "/api/rooms/$roomId/session/pause", owner).andExpect { status { isOk() } }.body()

    protected fun resume(owner: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.POST, "/api/rooms/$roomId/session/resume", owner).andExpect { status { isOk() } }.body()

    protected fun moveRequest(
        user: UUID,
        roomId: UUID,
        type: String = PASS,
        expectedVersion: Long? = null,
        gameCode: String = GameCodes.NOTHING,
    ) =
        call(HttpMethod.POST, "/api/games/$gameCode/rooms/$roomId/moves", user, MoveRequest(type, expectedVersion = expectedVersion))

    protected fun pass(user: UUID, roomId: UUID): RoomResponse =
        moveRequest(user, roomId).andExpect { status { isOk() } }.body()

    /** Комната владельца [owner] с игроками [players] (в порядке входа) и, если нужно, начатой партией. */
    protected fun roomWith(
        owner: UUID,
        vararg players: UUID,
        started: Boolean = false,
        settings: RoomSettingsDto = nothingSettings(),
    ): UUID {
        val roomId = createRoom(owner, settings).id
        players.forEach { join(it, roomId) }
        if (started) start(owner, roomId)
        return roomId
    }

    protected fun countRows(sql: String, vararg params: Pair<String, Any?>): Long =
        jdbcClient.sql("SELECT count(*) FROM $sql").also { spec -> params.forEach { spec.param(it.first, it.second) } }
            .query(Long::class.java).single()

    // --- CDC ---

    protected fun publishProfile(userId: UUID, nickname: String, avatar: Avatar = Avatar.AVATAR_CAT, version: Long = 0) {
        val event = UserProfileChanged.newBuilder()
            .setUserId(userId.toString())
            .setNickname(nickname)
            .setAvatar(avatar)
            .setVersion(version)
            .build()
        publishRaw(userId.toString(), event.toByteArray())
    }

    /** Отправляет сообщение так же, как Debezium EventRouter: ключ — id агрегата, значение — байты protobuf. */
    protected fun publishRaw(key: String, value: ByteArray) {
        producer.send(ProducerRecord(UserAuthTopics.USER_PROFILE, key, value)).get()
    }

    protected fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for: $description")
    }

    protected fun storedNickname(userId: UUID): String? =
        jdbcClient.sql("""SELECT nickname FROM "user_auth#user" WHERE id = :id""")
            .param("id", userId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    companion object {
        const val PASS = "PASS"

        private val producer by lazy {
            KafkaProducer<String, ByteArray>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to RedpandaTestContainer.bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
                ),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            PostgresTestContainer.registerProperties(registry)
            RedpandaTestContainer.registerProperties(registry)
            // Одна партиция, как гарантия порядка в тестах; в окружении порядок по ключу даёт партиционирование.
            RedpandaTestContainer.createCompactedTopic(UserAuthTopics.USER_PROFILE)
        }
    }
}
