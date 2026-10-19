package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.room.RoomEntityMapper
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomRole
import java.util.UUID

@Repository
class RoomMemberRepository(
    private val roomMemberPostgresqlRepository: RoomMemberPostgresqlRepository,
    private val roomEntityMapper: RoomEntityMapper,
) {

    /** Участники в порядке входа: от этого порядка зависят места в партии и передача владения. */
    fun findAll(roomId: UUID): List<RoomMemberModel> =
        roomMemberPostgresqlRepository.findAll(roomId).map(roomEntityMapper::toModel)

    fun insert(member: RoomMemberModel) = roomMemberPostgresqlRepository.insert(roomEntityMapper.toEntity(member))

    fun updateRole(roomId: UUID, userId: UUID, role: RoomRole) =
        roomMemberPostgresqlRepository.updateRole(roomId, userId, role.name)

    fun delete(roomId: UUID, userId: UUID) = roomMemberPostgresqlRepository.delete(roomId, userId)
}
