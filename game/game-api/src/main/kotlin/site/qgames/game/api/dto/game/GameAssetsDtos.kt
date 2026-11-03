package site.qgames.game.api.dto.game

import com.fasterxml.jackson.annotation.JsonProperty
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

/** Статичные данные «Круассана»: каталог типов тайлов и состав колоды по модулям. */
data class CroissantAssetsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CROISSANT])
    val gameCode: GameCode,
    /** Каталог типов тайлов: по нему клиент рисует доску; состояние партии ссылается на тип по его code. */
    val tiles: List<CroissantTileDto>,
    /** Модули игры (базовая игра `base`, дополнения): сколько тайлов каждого типа кладёт модуль. */
    val modules: List<CroissantModuleDto>,
) : GameAssetsDto

/** Модуль «Круассана»: базовая игра (`base`) или дополнение, с составом тайлов. */
data class CroissantModuleDto(
    /** Код модуля: `base` — базовая игра. */
    val code: String,
    /** Тайлы модуля по типам. */
    val tiles: List<CroissantModuleTileDto>,
)

/** Сколько тайлов одного типа кладёт модуль. */
data class CroissantModuleTileDto(
    /** Код типа тайла из `CroissantAssetsDto.tiles`. */
    val code: String,
    @field:Schema(minimum = "1")
    val count: Int,
)

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

/** Сторона тайла: какой элемент на неё выходит — тип и индекс в его списке тайла (`bakeries`, `trails` или `pastures`). */
data class CroissantTileEdgeDto(
    val type: CroissantEdge,
    val index: Int,
)

/** Стороны тайла: что выходит на N, E, S и W. Только отсюда известны стороны пекарен и троп. */
@Suppress("ConstructorParameterNaming")
data class CroissantTileEdgesDto(
    @get:JsonProperty("N")
    val N: CroissantTileEdgeDto,
    @get:JsonProperty("E")
    val E: CroissantTileEdgeDto,
    @get:JsonProperty("S")
    val S: CroissantTileEdgeDto,
    @get:JsonProperty("W")
    val W: CroissantTileEdgeDto,
)

/** Пекарня на тайле: её стороны — рёбра `edges` с её индексом. */
data class CroissantBakeryDto(
    /** Вывеска. */
    val sign: Boolean,
)

/**
 * Мучная тропа на тайле: её стороны — рёбра `edges` с её индексом. Тропа на две стороны и больше соединяет их и целей
 * не имеет; тропа на одну сторону входит ровно в одну цель — пекарню (`bakeryIndex`) или постройку тайла (`structure`).
 */
data class CroissantTrailDto(
    /** Индекс пекарни тайла (`bakeries`), в которую входит тропа; null — тропа идёт не в пекарню. */
    val bakeryIndex: Int?,
    /** Тропа заканчивается у постройки тайла (`structures`). */
    val structure: Boolean,
)

/** Постройка в центре тайла: сыроварня или сад, не обе сразу. */
data class CroissantStructuresDto(
    /** Сад: тропы заканчиваются у него, кондитера на сад не ставят. */
    val garden: Boolean,
    /** Сыроварня при аббатстве: участок для кондитера. */
    val dairy: Boolean,
)

/**
 * Луг на тайле: полуребра выхода и индексы пекарен тайла, которых касается этот кусок луга. Пекарни луга целиком
 * (по всем тайлам) — в участке партии (`CroissantSpotDto.bakeries`).
 */
data class CroissantPastureDto(
    val halves: List<CroissantHalfEdgeDto>,
    val adjacentBakeries: List<Int>,
)

/** Тип тайла «Круассана»: геометрия участков в базовой ориентации. */
data class CroissantTileDto(
    val code: String,
    /** Подпись тайла для интерфейса. */
    val title: String,
    /** Что выходит на каждую сторону. */
    val edges: CroissantTileEdgesDto,
    val bakeries: List<CroissantBakeryDto>,
    val trails: List<CroissantTrailDto>,
    /** Постройка в центре тайла. */
    val structures: CroissantStructuresDto,
    val pastures: List<CroissantPastureDto>,
)
