package site.qgames.game.model.room

import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchOperation
import site.qgames.common.docpatch.PathSegment
import java.time.Instant
import java.util.UUID

/**
 * Событие потока комнаты — одна операция над документом потока (комната, как в снапшоте). Значение — JSON-дерево
 * в форме контракта (участник, настройки), его собирает RoomEventPublisher в транзакции изменения. Коды — RoomEventCodes
 * контракта.
 */
data class RoomEvent(
    override val code: String,
    override val method: PatchMethod,
    override val path: List<PathSegment>,
    override val value: Any?,
) : PatchOperation

/**
 * Опубликованное событие комнаты (Postgres NOTIFY, канал room_events): событие целиком, со своим seq и моментом
 * изменения. Данные зафиксированы в транзакции изменения, диспетчер в БД не ходит.
 */
data class RoomEventModel(
    val roomId: UUID,
    val seq: Long,
    val at: Instant,
    val event: RoomEvent,
)

/** Результат подключения к потоку комнаты: id подключения и снапшот комнаты на seq [seq] для подключившегося. */
data class RoomConnectionModel(
    val connectionId: UUID,
    val seq: Long,
    val details: RoomDetailsModel,
)
