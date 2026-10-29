package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchPaths.id
import site.qgames.common.docpatch.PatchPaths.path
import site.qgames.common.docpatch.PathSegment
import site.qgames.game.api.dto.room.Avatar
import site.qgames.game.api.dto.room.RoomEventCodes
import site.qgames.game.api.dto.room.RoomEventDelete
import site.qgames.game.api.dto.room.RoomEventInfo
import site.qgames.game.api.dto.room.RoomEventPatch
import site.qgames.game.api.dto.room.RoomEventPut
import site.qgames.game.api.dto.room.RoomPatchEvent
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSnapshotDto
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.RoomStreamEventType
import site.qgames.game.api.dto.patch.DeleteData
import site.qgames.game.api.dto.patch.PatchData
import site.qgames.game.api.dto.patch.PatchEventMethod
import site.qgames.game.api.dto.patch.PutData
import site.qgames.game.mapper.patch.PatchJsonMapper
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomEvent
import site.qgames.game.model.room.RoomEventModel
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomRole as RoomRoleModel
import site.qgames.game.model.session.RoomSessionModel
import java.util.UUID

/**
 * Поток комнаты → DTO контракта game-api (RoomStreamEvent): снапшот, события-патчи документа потока (комнаты в форме
 * RoomResponse) и их кадры. Только преобразование, без обращений к данным.
 */
@Component
class RoomEventResponseMapper(
    private val roomResponseMapper: RoomResponseMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
    private val patchJsonMapper: PatchJsonMapper,
) {

    /** snapshot: seq и комната целиком, как в GET комнаты. */
    fun toSnapshot(seq: Long, details: RoomDetailsModel) = RoomSnapshotDto(seq, roomResponseMapper.toResponse(details))

    fun memberJoined(member: RoomMemberDetailsModel) =
        put(RoomEventCodes.MEMBER_JOINED, member(member.member.userId), roomResponseMapper.toResponse(member))

    fun memberLeft(userId: UUID) = RoomEvent(RoomEventCodes.MEMBER_LEFT, PatchMethod.DELETE, member(userId), null)

    fun memberLeaving(userId: UUID) = patch(RoomEventCodes.MEMBER_LEAVING, member(userId), mapOf(LEAVING to true))

    fun memberReturned(userId: UUID) = patch(RoomEventCodes.MEMBER_RETURNED, member(userId), mapOf(LEAVING to false))

    fun roleChanged(userId: UUID, role: RoomRoleModel) =
        patch(RoomEventCodes.ROLE_CHANGED, member(userId), mapOf(ROLE to RoomRole.valueOf(role.name)))

    fun profileChanged(member: RoomMemberDetailsModel) = patch(
        RoomEventCodes.PROFILE_CHANGED,
        member(member.member.userId),
        mapOf(NICKNAME to member.profile?.nickname, AVATAR to member.profile?.avatar?.let { Avatar.valueOf(it.name) }),
    )

    fun ownerChanged(ownerId: UUID?) = patch(RoomEventCodes.OWNER_CHANGED, emptyList(), mapOf(OWNER_ID to ownerId))

    fun settingsChanged(settings: RoomSettingsModel) =
        put(RoomEventCodes.SETTINGS_CHANGED, path(SETTINGS), roomSettingsResponseMapper.toResponse(settings))

    fun sessionStarted(session: RoomSessionModel) = patch(
        RoomEventCodes.SESSION_STARTED,
        emptyList(),
        mapOf(STATUS to RoomStatus.IN_GAME, SESSION to roomResponseMapper.toResponse(session)),
    )

    fun sessionEnded(session: RoomSessionModel) = patch(
        RoomEventCodes.SESSION_ENDED,
        emptyList(),
        mapOf(STATUS to RoomStatus.WAITING, SESSION to roomResponseMapper.toResponse(session)),
    )

    fun roomDeleted() = RoomEvent(RoomEventCodes.ROOM_DELETED, PatchMethod.INFO, emptyList(), emptyMap<String, Any?>())

    /** Тип сообщения SSE для события: метод в нижнем регистре. */
    fun toType(event: RoomEvent): String = RoomStreamEventType.valueOf(event.method.name).code

    /** Последнее событие потока: после него поток закрывается. */
    fun isLast(event: RoomEvent): Boolean = event.code == RoomEventCodes.ROOM_DELETED

    /** Кадр события `{seq, at, method, code, data}`. */
    fun toFrame(model: RoomEventModel): RoomPatchEvent {
        val (_, seq, at, event) = model
        val method = PatchEventMethod.valueOf(event.method.name)
        val path = patchJsonMapper.path(event.path)
        return when (method) {
            PatchEventMethod.PUT -> RoomEventPut(seq, at, method, event.code, PutData(path, event.value))
            PatchEventMethod.PATCH -> RoomEventPatch(seq, at, method, event.code, PatchData(path, objectOf(event)))
            PatchEventMethod.DELETE -> RoomEventDelete(seq, at, method, event.code, DeleteData(path))
            PatchEventMethod.INFO -> RoomEventInfo(seq, at, method, event.code, objectOf(event))
        }
    }

    private fun member(userId: UUID) = path(MEMBERS, id(userId))

    private fun put(code: String, path: List<PathSegment>, value: Any?) =
        RoomEvent(code, PatchMethod.PUT, path, value)

    private fun patch(code: String, path: List<PathSegment>, value: Map<String, Any?>) =
        RoomEvent(code, PatchMethod.PATCH, path, value)

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(event: RoomEvent): Map<String, Any?> =
        event.value as? Map<String, Any?> ?: error("Value of ${event.method} ${event.code} must be an object")

    private companion object {
        const val MEMBERS = "members"
        const val SETTINGS = "settings"
        const val LEAVING = "leaving"
        const val ROLE = "role"
        const val NICKNAME = "nickname"
        const val AVATAR = "avatar"
        const val OWNER_ID = "ownerId"
        const val STATUS = "status"
        const val SESSION = "session"
    }
}
