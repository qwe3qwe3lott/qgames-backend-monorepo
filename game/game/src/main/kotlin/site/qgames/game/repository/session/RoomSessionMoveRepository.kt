package site.qgames.game.repository.session

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.session.RoomSessionEntityMapper
import site.qgames.game.model.session.RoomSessionMoveModel

@Repository
class RoomSessionMoveRepository(
    private val roomSessionMovePostgresqlRepository: RoomSessionMovePostgresqlRepository,
    private val roomSessionEntityMapper: RoomSessionEntityMapper,
) {

    fun insert(move: RoomSessionMoveModel) = roomSessionMovePostgresqlRepository.insert(roomSessionEntityMapper.toEntity(move))
}
