package site.qgames.game.mapper.game

import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.CroissantAssetsDto
import site.qgames.game.api.dto.game.CroissantBakeryDto
import site.qgames.game.api.dto.game.CroissantDirection
import site.qgames.game.api.dto.game.CroissantEdge
import site.qgames.game.api.dto.game.CroissantHalfEdgeDto
import site.qgames.game.api.dto.game.CroissantModuleDto
import site.qgames.game.api.dto.game.CroissantModuleTileDto
import site.qgames.game.api.dto.game.CroissantPastureDto
import site.qgames.game.api.dto.game.CroissantStructuresDto
import site.qgames.game.api.dto.game.CroissantTileDto
import site.qgames.game.api.dto.game.CroissantTileEdgeDto
import site.qgames.game.api.dto.game.CroissantTileEdgesDto
import site.qgames.game.api.dto.game.CroissantTrailDto
import site.qgames.game.api.dto.game.GameAssetsDto
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.game.GameRulesDto
import site.qgames.game.api.dto.game.GameSummaryDto
import site.qgames.game.api.dto.game.TimeControlLimitsDto
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameDetailsModel
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GameRulesModel
import site.qgames.games.croissant.CroissantAssets
import site.qgames.games.croissant.CroissantEdgeSpec
import site.qgames.games.croissant.CroissantTileType
import site.qgames.game.api.dto.game.GameCode as GameCodeDto

/** Каталог игр → DTO контракта. */
@Component
class GameResponseMapper(private val roomSettingsResponseMapper: RoomSettingsResponseMapper) {

    fun toListResponse(games: List<GameModel>) = GameListResponse(
        games = games.map {
            GameSummaryDto(
                code = toDto(it.code),
                title = it.title,
                description = it.description,
            )
        },
    )

    fun toResponse(details: GameDetailsModel) = GameDetailsResponse(
        code = toDto(details.game.code),
        title = details.game.title,
        description = details.game.description,
        rules = toDto(details.rules),
        assets = details.rules.assets?.let(::toAssetsResponse),
    )

    fun toDto(code: GameCode): GameCodeDto = GameCodeDto.valueOf(code.name)

    /** Статичные данные игры (GameRules.assets) → DTO контракта; новая игра со своими данными добавляет ветку. */
    private fun toAssetsResponse(assets: Any): GameAssetsDto = when (assets) {
        is CroissantAssets -> CroissantAssetsDto(
            gameCode = GameCodeDto.CROISSANT,
            tiles = assets.tiles.map(::toDto),
            modules = assets.modules.map { module ->
                CroissantModuleDto(
                    code = module.code,
                    tiles = module.counts.map { (code, count) -> CroissantModuleTileDto(code, count) },
                )
            },
        )
        else -> error("No DTO for game assets ${assets::class.qualifiedName}")
    }

    private fun toDto(tile: CroissantTileType) = CroissantTileDto(
        code = tile.code,
        title = tile.title,
        edges = CroissantTileEdgesDto(
            N = toDto(tile.edges.n),
            E = toDto(tile.edges.e),
            S = toDto(tile.edges.s),
            W = toDto(tile.edges.w),
        ),
        bakeries = tile.bakeries.map { CroissantBakeryDto(it.sign) },
        trails = tile.trails.map { CroissantTrailDto(bakeryIndex = it.bakeryIndex, structure = it.structure) },
        structures = CroissantStructuresDto(garden = tile.structures.garden, dairy = tile.structures.dairy),
        pastures = tile.pastures.map { pasture ->
            CroissantPastureDto(
                halves = pasture.halves.map { CroissantHalfEdgeDto(CroissantDirection.valueOf(it.dir.name), it.half) },
                adjacentBakeries = pasture.adjacentBakeries,
            )
        },
    )

    private fun toDto(edge: CroissantEdgeSpec) = CroissantTileEdgeDto(CroissantEdge.valueOf(edge.type.name), edge.index)

    private fun toDto(rules: GameRulesModel) = GameRulesDto(
        minPlayers = rules.minPlayers,
        maxPlayers = rules.maxPlayers,
        spectators = roomSettingsResponseMapper.toDto(rules.spectators),
        timeControl = TimeControlLimitsDto(
            optional = rules.timeControlOptional,
            baseSeconds = roomSettingsResponseMapper.toDto(rules.baseSeconds),
            bonusSeconds = roomSettingsResponseMapper.toDto(rules.bonusSeconds),
        ),
        gameSettingsLimits = roomSettingsResponseMapper.toLimitsResponse(rules.settingsLimits),
        defaultSettings = roomSettingsResponseMapper.toResponse(rules.defaultSettings),
    )
}
