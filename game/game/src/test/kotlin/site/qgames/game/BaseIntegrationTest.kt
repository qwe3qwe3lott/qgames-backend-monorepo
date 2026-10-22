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
import site.qgames.game.gatewayapi.dto.room.CheckersSettingsDto
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.game.GameCodes
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.job.room.RoomOwnerGraceJob
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.session.SessionJournalService
import site.qgames.game.service.session.SessionStateService
import site.qgames.game.service.session.SessionStreamService
import site.qgames.game.stream.room.RoomStreamRegistry
import site.qgames.game.stream.session.SessionStreamRegistry
import site.qgames.games.api.session.Session
import site.qgames.games.api.session.ViewerKey
import org.junit.jupiter.api.AfterEach
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
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
    protected lateinit var roomOwnerGraceJob: RoomOwnerGraceJob

    @Autowired
    protected lateinit var roomStreamRegistry: RoomStreamRegistry

    @Autowired
    protected lateinit var sessionStreamRegistry: SessionStreamRegistry

    @Autowired
    protected lateinit var sessionStateService: SessionStateService

    @Autowired
    protected lateinit var sessionJournalService: SessionJournalService

    @Autowired
    protected lateinit var roomSessionRepository: RoomSessionRepository

    /** Правила «игры в ничто»: шпион, чтобы проверять ветки, недостижимые с настоящими правилами (сброс — после теста). */
    @MockkSpyBean
    protected lateinit var nothingGameRules: NothingGameRules

    /** Журнал партии: шпион, чтобы проверять, какие события дочитывает сборка состояния. */
    @MockkSpyBean
    protected lateinit var roomSessionEventRepository: RoomSessionEventRepository

    /** Поток партии: шпион, чтобы вклиниваться между регистрацией подписчика и снапшотом (гонки). */
    @MockkSpyBean
    protected lateinit var sessionStreamService: SessionStreamService

    /** Потоки, открытые тестом, закрываются после него (отключения от комнат — до очистки БД следующего теста). */
    @AfterEach
    fun closeRoomStreams() {
        roomStreamRegistry.closeAll()
        sessionStreamRegistry.closeAll()
    }

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
    ) = RoomSettingsDto(TimeControlDto(mode, moveSeconds), NothingSettingsDto(GameCode.NOTHING, rounds))

    protected fun createRoomRequest(owner: UUID?, body: Any, gameCode: String = GameCodes.NOTHING): ResultActionsDsl =
        call(HttpMethod.POST, "/api/games/$gameCode/rooms", owner, body)

    /**
     * Создаёт комнату игры из настроек и, как портал после создания, подключает владельца к её потоку.
     */
    protected fun createRoom(owner: UUID, settings: RoomSettingsDto = nothingSettings()): RoomResponse {
        val gameCode = when (settings.gameSettings) {
            is NothingSettingsDto -> GameCodes.NOTHING
            is CheckersSettingsDto -> GameCodes.CHECKERS
        }
        val created: RoomResponse = createRoomRequest(owner, CreateRoomRequest(settings), gameCode)
            .andExpect { status { isOk() } }
            .body()
        return join(owner, created.id)
    }

    protected fun updateSettingsRequest(user: UUID, roomId: UUID, body: Any, gameCode: String = GameCodes.NOTHING): ResultActionsDsl =
        call(HttpMethod.PUT, "/api/games/$gameCode/rooms/$roomId/settings", user, body)

    protected fun room(roomId: UUID, viewer: UUID): RoomResponse =
        call(HttpMethod.GET, "/api/rooms/$roomId", viewer).andExpect { status { isOk() } }.body()

    /** Открывает поток событий комнаты (SSE) — вход в комнату. Поток остаётся открытым до [leave] или конца теста. */
    protected fun openStream(user: UUID, roomId: UUID): MvcResult =
        mockMvc.get("/api/rooms/$roomId/events") {
            header(QgamesHeaders.USER_ID, user.toString())
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

    /** Вход в комнату — подключение к её потоку; возвращает комнату глазами вошедшего. */
    protected fun join(user: UUID, roomId: UUID): RoomResponse {
        openStream(user, roomId)
        return room(roomId, user)
    }

    /** Выход — закрытие всех потоков пользователя в комнате (все вкладки). */
    protected fun leave(user: UUID, roomId: UUID) {
        streams(user, roomId).forEach { it.close() }
    }

    /** Открытые потоки пользователя в комнате на этом инстансе. */
    protected fun streams(user: UUID, roomId: UUID) = roomStreamRegistry.subscribers(roomId).filter { it.userId == user }

    /** Сообщения SSE потока [stream] к этому моменту: пары (тип события, data). Комментарии пропускаются. */
    protected fun events(stream: MvcResult): List<Pair<String, String>> =
        stream.response.getContentAsString(Charsets.UTF_8).split("\n\n")
            .filter { it.isNotBlank() }
            .mapNotNull { message ->
                val lines = message.lines()
                val type = lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim() ?: return@mapNotNull null
                type to lines.filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:") }
            }

    /** Ждёт, пока в потоке не наберётся [count] событий, и возвращает их. */
    protected fun awaitEvents(stream: MvcResult, count: Int): List<Pair<String, String>> {
        awaitUntil("$count events in the stream") { events(stream).size >= count }
        return events(stream)
    }

    /**
     * Выход, доведённый до конца: пользователь закрыл потоки, а окно владения (если он был владельцем) истекло и
     * RoomOwnerGraceJob передал владение — так, как будто он не вернулся вовремя.
     */
    protected fun leaveCompletely(user: UUID, roomId: UUID) {
        leave(user, roomId)
        expireOwnerGrace(roomId)
        roomOwnerGraceJob.run()
    }

    /** Сдвигает срок окна владения комнаты в прошлое (по расписанию RoomOwnerGraceJob в тестах выключен). */
    protected fun expireOwnerGrace(roomId: UUID) {
        jdbcClient.sql(
            "UPDATE room SET owner_grace_deadline = now() - interval '1 second' WHERE id = :roomId AND owner_grace_deadline IS NOT NULL",
        )
            .param("roomId", roomId)
            .update()
    }

    /** Окно владения комнаты: (прежний владелец, срок) или null. */
    protected fun ownerGrace(roomId: UUID): Pair<UUID, Instant>? =
        jdbcClient.sql("SELECT owner_grace_user_id, owner_grace_deadline FROM room WHERE id = :roomId")
            .param("roomId", roomId)
            .query { rs, _ ->
                rs.getObject("owner_grace_user_id", UUID::class.java)?.let { it to rs.getTimestamp("owner_grace_deadline").toInstant() }
            }
            .list()
            .single()

    protected fun isMember(roomId: UUID, user: UUID): Boolean =
        countRows("room_member WHERE room_id = :roomId AND user_id = :userId", "roomId" to roomId, "userId" to user) == 1L

    /** Когда игрок партии ушёл (room_member.left_at); null — присутствует. Участник должен быть в комнате. */
    protected fun leftAt(roomId: UUID, user: UUID): Instant? =
        jdbcClient.sql("SELECT left_at FROM room_member WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", user)
            .query { rs, _ -> rs.getTimestamp("left_at")?.toInstant() }
            .list()
            .single()

    protected fun changeRole(user: UUID, roomId: UUID, role: RoomRole): RoomResponse =
        call(HttpMethod.PUT, "/api/rooms/$roomId/role", user, ChangeRoleRequest(role)).andExpect { status { isOk() } }.body()

    protected fun start(owner: UUID, roomId: UUID): RoomResponse =
        call(HttpMethod.POST, "/api/rooms/$roomId/session", owner).andExpect { status { isOk() } }.body()

    protected fun cancel(owner: UUID, roomId: UUID) {
        call(HttpMethod.DELETE, "/api/rooms/$roomId/session", owner).andExpect { status { isNoContent() } }
    }

    protected fun pause(owner: UUID, roomId: UUID) {
        call(HttpMethod.POST, "/api/rooms/$roomId/session/pause", owner).andExpect { status { isNoContent() } }
    }

    protected fun resume(owner: UUID, roomId: UUID) {
        call(HttpMethod.POST, "/api/rooms/$roomId/session/resume", owner).andExpect { status { isNoContent() } }
    }

    protected fun moveRequest(user: UUID, roomId: UUID, type: String = PASS, gameCode: String = GameCodes.NOTHING) =
        call(HttpMethod.POST, "/api/games/$gameCode/rooms/$roomId/moves", user, MoveRequest(type))

    protected fun pass(user: UUID, roomId: UUID) {
        moveRequest(user, roomId).andExpect { status { isNoContent() } }
    }

    // --- Партии ---

    /** Id последней партии комнаты. */
    protected fun sessionId(roomId: UUID): UUID =
        jdbcClient.sql("SELECT id FROM room_session WHERE room_id = :roomId ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("roomId", roomId)
            .query(UUID::class.java)
            .single()

    /** Полное состояние последней партии комнаты — свёртка журнала (как его видит сервер). */
    protected fun fullState(roomId: UUID): Session = sessionJournalService.full(roomSessionRepository.findById(sessionId(roomId))!!).session

    /** Полный документ последней партии комнаты {status, meta, state} — свёртка журнала. */
    protected fun fullDocument(roomId: UUID): Map<String, Any?> =
        sessionStateService.state(roomSessionRepository.findById(sessionId(roomId))!!, ViewerKey.Full).document

    /** Журнал последней партии комнаты: пары (seq, код) по порядку. */
    protected fun journal(roomId: UUID): List<Pair<Long, String>> =
        jdbcClient.sql("SELECT seq, code FROM room_session_event WHERE session_id = :id ORDER BY seq")
            .param("id", sessionId(roomId))
            .query { rs, _ -> rs.getLong("seq") to rs.getString("code") }
            .list()

    /** Открывает поток партии (SSE). Поток остаётся открытым до конца партии или конца теста. */
    protected fun openSessionStream(
        user: UUID,
        roomId: UUID,
        sessionId: UUID = sessionId(roomId),
        gameCode: String = GameCodes.NOTHING,
    ): MvcResult =
        mockMvc.get("/api/games/$gameCode/rooms/$roomId/sessions/$sessionId/events") {
            header(QgamesHeaders.USER_ID, user.toString())
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

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

    /** Статус публикации игры [code] в каталоге (позже его будет менять админка). */
    protected fun setPublishStatus(code: String, status: String) {
        jdbcClient.sql("UPDATE game SET publish_status = :status WHERE code = :code")
            .param("status", status)
            .param("code", code)
            .update()
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
