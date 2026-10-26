package site.qgames.game

import org.springframework.beans.factory.annotation.Autowired
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import site.qgames.BaseIntegrationTest
import site.qgames.game.table.game.GameTable
import site.qgames.game.table.room.RoomMemberTable
import site.qgames.game.table.room.RoomTable
import site.qgames.game.table.session.RoomSessionEventTable
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.dto.user.UpdateProfileRequest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import site.qgames.game.api.dto.room.ChangeRoleRequest
import site.qgames.game.api.dto.room.CheckersSettingsDto
import site.qgames.game.api.dto.room.CreateRoomRequest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.NothingSettingsDto
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.room.TimeControlMode
import site.qgames.game.job.room.RoomOwnerGraceJob
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.session.SessionJournalService
import site.qgames.game.service.session.SessionStateService
import site.qgames.game.stream.room.RoomStreamRegistry
import site.qgames.game.stream.session.SessionStreamRegistry
import site.qgames.games.api.session.Session
import site.qgames.games.api.session.ViewerKey
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

/**
 * Хелперы тестов модуля game: комнаты, потоки (SSE), партии. Пользователь запроса — access-JWT игрока
 * ([asUser]); профили (ник, аватар) game читает из таблицы user — фикстуры init/user-auth.sql.
 */
abstract class GameIntegrationTest : BaseIntegrationTest() {

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

    protected fun call(method: HttpMethod, path: String, userId: UUID?, body: Any? = null): ResultActionsDsl =
        mockMvc.request(method, path) {
            asUser(userId)
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(it)
            }
        }

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
            asUser(user)
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

    /** Открытые потоки пользователя в комнате. */
    protected fun streams(user: UUID, roomId: UUID) = roomStreamRegistry.subscribers(roomId).filter { it.userId == user }

    /** Сообщения SSE потока [stream] к этому моменту: пары (тип события, data). Комментарии пропускаются. */
    protected fun events(stream: MvcResult): List<Pair<String, String>> =
        // Поток пишется асинхронно и по частям: учитываются только сообщения, уже завершённые пустой строкой, иначе
        // недописанное `event: snapshot` с пустым `data` выглядело бы как снапшот без состояния.
        stream.response.getContentAsString(Charsets.UTF_8).substringBeforeLast("\n\n", "").split("\n\n")
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
        db {
            RoomTable.update({ (RoomTable.id eq roomId) and RoomTable.ownerGraceDeadline.isNotNull() }) {
                it[ownerGraceDeadline] = Instant.now().minusSeconds(1)
            }
        }
    }

    /** Окно владения комнаты: (прежний владелец, срок) или null. */
    protected fun ownerGrace(roomId: UUID): Pair<UUID, Instant>? = db {
        val row = RoomTable.selectAll().where { RoomTable.id eq roomId }.single()
        row[RoomTable.ownerGraceUserId]?.let { it to row[RoomTable.ownerGraceDeadline]!! }
    }

    protected fun isMember(roomId: UUID, user: UUID): Boolean =
        count(RoomMemberTable) { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq user) } == 1L

    /** Когда игрок партии ушёл (room_member.left_at); null — присутствует. Участник должен быть в комнате. */
    protected fun leftAt(roomId: UUID, user: UUID): Instant? = db {
        RoomMemberTable.selectAll()
            .where { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq user) }
            .single()[RoomMemberTable.leftAt]
    }

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
    protected fun sessionId(roomId: UUID): UUID = roomSessionRepository.findLatest(roomId)!!.id

    /** Полное состояние последней партии комнаты — свёртка журнала (как его видит сервер). */
    protected fun fullState(roomId: UUID): Session = sessionJournalService.full(roomSessionRepository.findById(sessionId(roomId))!!).session

    /** Полный документ последней партии комнаты {status, meta, state} — свёртка журнала. */
    protected fun fullDocument(roomId: UUID): Map<String, Any?> =
        sessionStateService.state(roomSessionRepository.findById(sessionId(roomId))!!, ViewerKey.Full).document

    /** Журнал последней партии комнаты: пары (seq, код) по порядку. */
    protected fun journal(roomId: UUID): List<Pair<Long, String>> {
        val sessionId = sessionId(roomId)
        return db {
            RoomSessionEventTable.selectAll()
                .where { RoomSessionEventTable.sessionId eq sessionId }
                .orderBy(RoomSessionEventTable.seq to SortOrder.ASC)
                .map { it[RoomSessionEventTable.seq] to it[RoomSessionEventTable.code] }
        }
    }

    /** Открывает поток партии (SSE). Поток остаётся открытым до конца партии или конца теста. */
    protected fun openSessionStream(
        user: UUID,
        roomId: UUID,
        sessionId: UUID = sessionId(roomId),
        gameCode: String = GameCodes.NOTHING,
    ): MvcResult =
        mockMvc.get("/api/games/$gameCode/rooms/$roomId/sessions/$sessionId/events") {
            asUser(user)
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
        db { GameTable.update({ GameTable.code eq code }) { it[publishStatus] = status } }
    }


    // --- Профили (таблица user модуля user-auth) ---

    /** Меняет профиль через ручку user-auth — так, как это делает пользователь. */
    protected fun updateProfile(userId: UUID, nickname: String, avatar: Avatar = Avatar.CAT) {
        call(HttpMethod.PATCH, "/api/users/me/profile", userId, UpdateProfileRequest(nickname, avatar)).andExpect { status { isOk() } }
    }

    companion object {
        const val PASS = "PASS"
    }
}
