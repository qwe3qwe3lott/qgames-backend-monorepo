package site.qgames.game.api.dto.game

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Статичные данные конкретной игры для отрисовки партии (например, каталог тайлов «Круассана»), вид — по полю gameCode.
 * Одни и те же для всех партий игры, поэтому в состояние партии не входят: состояние ссылается на них по кодам.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = CroissantAssetsDto::class, name = GameCodes.CROISSANT),
)
@Schema(
    description = "Статичные данные конкретной игры для отрисовки партии, вид — по полю gameCode",
    oneOf = [CroissantAssetsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [
        DiscriminatorMapping(value = GameCodes.CROISSANT, schema = CroissantAssetsDto::class),
    ],
)
sealed interface GameAssetsDto

/** Статичные данные «Круассана»: каталог типов тайлов. */
data class CroissantAssetsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CROISSANT])
    val gameCode: GameCode,
    /** Каталог типов тайлов: по нему клиент рисует доску; состояние партии ссылается на тип по его code. */
    val tiles: List<CroissantTileDto>,
) : GameAssetsDto

/** Сторона тайла «Круассана»; в JSON — имя значения. */
enum class CroissantDirection {
    N,
    E,
    S,
    W,
}

/** Чем занята сторона тайла: пекарня, мучная тропа или луг. */
enum class CroissantEdge {
    BAKERY,
    TRAIL,
    PASTURE,
}

/** Полуребро: сторона и её половина (0 — первая по часовой стрелке). */
data class CroissantHalfEdgeDto(
    val dir: CroissantDirection,
    val half: Int,
)

/** Пекарня на тайле: стороны квартала и вывеска. */
data class CroissantBakeryDto(
    val sides: List<CroissantDirection>,
    val sign: Boolean,
)

/** Мучная тропа на тайле: стороны, на которые она выходит. */
data class CroissantTrailDto(
    val sides: List<CroissantDirection>,
)

/** Луг на тайле: полуребра выхода и индексы примыкающих пекарен тайла. */
data class CroissantPastureDto(
    val halves: List<CroissantHalfEdgeDto>,
    val bakeries: List<Int>,
)

/** Тип тайла «Круассана»: геометрия участков в базовой ориентации. */
data class CroissantTileDto(
    val code: String,
    /** Подпись тайла для интерфейса. */
    val title: String,
    /** Чем занята каждая сторона: N, E, S, W. */
    val edges: List<CroissantEdge>,
    val bakeries: List<CroissantBakeryDto>,
    val trails: List<CroissantTrailDto>,
    /** Сыроварня при аббатстве на этом тайле. */
    val dairy: Boolean,
    val pastures: List<CroissantPastureDto>,
)
