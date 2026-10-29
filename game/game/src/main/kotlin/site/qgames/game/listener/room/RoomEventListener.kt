package site.qgames.game.listener.room

import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomEventModel
import site.qgames.common.docpatch.PatchMethod
import site.qgames.game.mapper.patch.PatchJsonMapper
import site.qgames.game.model.room.RoomEvent
import site.qgames.game.model.session.SessionEventsNotificationModel
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.publisher.session.SessionEventPublisher
import site.qgames.game.stream.room.RoomStreamDispatcher
import site.qgames.game.stream.room.RoomStreamRegistry
import site.qgames.game.stream.session.SessionStreamDispatcher
import site.qgames.game.stream.session.SessionStreamRegistry
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Принимает события комнат и партий (`pg_notify` в транзакциях изменений): `LISTEN room_events` и
 * `LISTEN session_events` на одном выделенном JDBC-соединении вне пула (пул отдаёт соединения на время запроса, а
 * подписка должна жить постоянно). LISTEN — сырой SQL, единственное разрешённое исключение вместе с `pg_notify`
 * (CLAUDE.md §4). Цикл
 * `getNotifications` идёт на виртуальном потоке, уведомления раздают [RoomStreamDispatcher] и [SessionStreamDispatcher]
 * в порядке их прихода (= порядке коммитов).
 *
 * Пока соединение было разорвано, уведомления могли потеряться, поэтому при обрыве и после переподключения все
 * локальные потоки комнат и партий закрываются: клиенты переподключатся и получат свежий снапшот.
 *
 * `getNotifications` в сокет не пишет, поэтому «полуоткрытое» соединение (обрыв без FIN/RST) само не обнаружится:
 * раз в [VALIDATE_INTERVAL_MILLIS] без уведомлений соединение проверяется запросом (`isValid`), плюс включён TCP
 * keepalive. Проверка не прошла — та же ветка, что при ошибке: закрыть потоки и переподключиться.
 *
 * Стартует раньше веб-сервера (и останавливается позже): первые подписчики не должны пропустить свои события.
 */
@Component
class RoomEventListener(
    private val dispatcher: RoomStreamDispatcher,
    private val registry: RoomStreamRegistry,
    private val sessionDispatcher: SessionStreamDispatcher,
    private val sessionRegistry: SessionStreamRegistry,
    private val jsonMapper: JsonMapper,
    private val patchJsonMapper: PatchJsonMapper,
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
) : SmartLifecycle {

    @Volatile
    private var running = false

    @Volatile
    private var connection: Connection? = null

    private var thread: Thread? = null

    override fun start() {
        running = true
        val firstListen = CountDownLatch(1)
        thread = Thread.ofVirtual().name("room-event-listener").start { loop(firstListen) }
        if (!firstListen.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("LISTEN {} is not established yet, retrying in background", RoomEventPublisher.CHANNEL)
        }
    }

    override fun stop() {
        running = false
        runCatching { connection?.close() }
        thread?.interrupt()
        thread?.join(TimeUnit.SECONDS.toMillis(STOP_TIMEOUT_SECONDS))
    }

    override fun isRunning() = running

    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE - PHASE_BEFORE_WEB_SERVER

    private fun loop(firstListen: CountDownLatch) {
        var reconnect = false
        while (running) {
            try {
                DriverManager.getConnection(url, connectionProperties()).use { conn ->
                    connection = conn
                    conn.createStatement().use {
                        it.execute("LISTEN ${RoomEventPublisher.CHANNEL}")
                        it.execute("LISTEN ${SessionEventPublisher.CHANNEL}")
                    }
                    if (reconnect) {
                        log.info("LISTEN {} is re-established", RoomEventPublisher.CHANNEL)
                        closeAllStreams()
                    }
                    firstListen.countDown()
                    val pg = conn.unwrap(PGConnection::class.java)
                    var lastActivity = System.nanoTime()
                    while (running) {
                        val notifications = pg.getNotifications(POLL_TIMEOUT_MILLIS)
                        if (!notifications.isNullOrEmpty()) {
                            lastActivity = System.nanoTime()
                            notifications.forEach(::dispatch)
                        } else if (System.nanoTime() - lastActivity >= TimeUnit.MILLISECONDS.toNanos(VALIDATE_INTERVAL_MILLIS)) {
                            if (!conn.isValid(VALIDATE_TIMEOUT_SECONDS)) {
                                throw SQLException("LISTEN connection is not valid")
                            }
                            lastActivity = System.nanoTime()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                log.error("LISTEN {} connection is lost, closing local streams", RoomEventPublisher.CHANNEL, e)
                closeAllStreams()
                reconnect = true
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            } finally {
                connection = null
            }
        }
    }

    private fun connectionProperties() = Properties().apply {
        setProperty("user", username)
        setProperty("password", password)
        setProperty("tcpKeepAlive", "true")
        setProperty("ApplicationName", "game-event-listener")
    }

    private fun dispatch(notification: PGNotification) {
        when (notification.name) {
            SessionEventPublisher.CHANNEL -> parseSession(notification.parameter)?.let(sessionDispatcher::dispatch)
            else -> parse(notification.parameter)?.let(dispatcher::dispatch)
        }
    }

    private fun closeAllStreams() {
        registry.closeAll()
        sessionRegistry.closeAll()
    }

    private fun parseSession(payload: String): SessionEventsNotificationModel? = try {
        val node = jsonMapper.readTree(payload)
        SessionEventsNotificationModel(
            sessionId = UUID.fromString(node.path(SessionEventPublisher.SESSION_ID).asString()),
            fromSeq = node.path(SessionEventPublisher.FROM_SEQ).asLong(),
            toSeq = node.path(SessionEventPublisher.TO_SEQ).asLong(),
        )
    } catch (e: Exception) {
        log.error("Skipped malformed session event {}", payload, e)
        null
    }

    private fun parse(payload: String): RoomEventModel? = try {
        val fields = jsonMapper.readValue(payload, Map::class.java)
        val code = fields[RoomEventPublisher.CODE] as String
        val method = PatchMethod.valueOf(fields[RoomEventPublisher.METHOD] as String)
        val (path, value) = patchJsonMapper.read(method, code, fields[RoomEventPublisher.DATA])
        RoomEventModel(
            roomId = UUID.fromString(fields[RoomEventPublisher.ROOM_ID] as String),
            seq = (fields[RoomEventPublisher.SEQ] as Number).toLong(),
            at = Instant.parse(fields[RoomEventPublisher.AT] as String),
            event = RoomEvent(code, method, path, value),
        )
    } catch (e: Exception) {
        log.error("Skipped malformed room event {}", payload, e)
        null
    }

    companion object {
        private const val POLL_TIMEOUT_MILLIS = 1_000
        /** Как часто без уведомлений проверять, живо ли LISTEN-соединение. */
        private const val VALIDATE_INTERVAL_MILLIS = 10_000L
        private const val VALIDATE_TIMEOUT_SECONDS = 5
        private const val RECONNECT_DELAY_MILLIS = 1_000L
        private const val START_TIMEOUT_SECONDS = 10L
        private const val STOP_TIMEOUT_SECONDS = 5L
        private const val PHASE_BEFORE_WEB_SERVER = 4096
        private val log = LoggerFactory.getLogger(RoomEventListener::class.java)
    }
}
