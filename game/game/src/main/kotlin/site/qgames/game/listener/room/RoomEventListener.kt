package site.qgames.game.listener.room

import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomEventModel
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.stream.room.RoomStreamDispatcher
import site.qgames.game.stream.room.RoomStreamRegistry
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Принимает события комнат из шины между инстансами: `LISTEN room_events` на одном выделенном JDBC-соединении вне пула
 * (пул отдаёт соединения на время запроса, а подписка должна жить постоянно). Цикл `getNotifications` идёт на
 * виртуальном потоке, уведомления раздаёт [RoomStreamDispatcher] в порядке их прихода (= порядке коммитов).
 *
 * Пока соединение было разорвано, уведомления могли потеряться, поэтому при обрыве и после переподключения все
 * локальные потоки закрываются: клиенты переподключатся и получат свежий снапшот.
 *
 * `getNotifications` в сокет не пишет, поэтому «полуоткрытое» соединение (обрыв без FIN/RST) само не обнаружится:
 * раз в [VALIDATE_INTERVAL_MILLIS] без уведомлений соединение проверяется запросом (`isValid`), плюс включён TCP
 * keepalive. Проверка не прошла — та же ветка, что при ошибке: закрыть потоки и переподключиться.
 *
 * Стартует раньше веб-сервера (и останавливается позже): первые подписчики не должны пропустить свой маркер.
 */
@Component
class RoomEventListener(
    private val dispatcher: RoomStreamDispatcher,
    private val registry: RoomStreamRegistry,
    private val jsonMapper: JsonMapper,
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
                    conn.createStatement().use { it.execute("LISTEN ${RoomEventPublisher.CHANNEL}") }
                    if (reconnect) {
                        log.info("LISTEN {} is re-established", RoomEventPublisher.CHANNEL)
                        registry.closeAll()
                    }
                    firstListen.countDown()
                    val pg = conn.unwrap(PGConnection::class.java)
                    var lastActivity = System.nanoTime()
                    while (running) {
                        val notifications = pg.getNotifications(POLL_TIMEOUT_MILLIS)
                        if (!notifications.isNullOrEmpty()) {
                            lastActivity = System.nanoTime()
                            notifications.forEach { notification -> parse(notification.parameter)?.let(dispatcher::dispatch) }
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
                log.error("LISTEN {} connection is lost, closing local room streams", RoomEventPublisher.CHANNEL, e)
                registry.closeAll()
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
        setProperty("ApplicationName", "game-room-event-listener")
    }

    private fun parse(payload: String): RoomEventModel? = try {
        val node = jsonMapper.readTree(payload)
        val type = RoomEventType.fromCode(node.path(RoomEventPublisher.TYPE).asString())
            ?: error("Unknown room event type")
        RoomEventModel(
            roomId = UUID.fromString(node.path(RoomEventPublisher.ROOM_ID).asString()),
            type = type,
            subscriberId = node.path(RoomEventPublisher.SUBSCRIBER_ID).takeIf { it.isString }?.let { UUID.fromString(it.asString()) },
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
