package site.qgames.games.croissant

import site.qgames.games.core.TimeBonusType
import site.qgames.games.core.TimeControlSettings

/** Simple-настройки: пресет времени. Правила у «Круассана» одни, вариантов нет. */
data class CroissantSimpleSettings(val time: CroissantTime)

/** Advanced-настройки партии: сколько кондитеров у игрока и часы. */
data class CroissantAdvancedSettings(
    /** Фишки кондитеров у каждого игрока. */
    val confectioners: Int,
    val timeControl: TimeControlSettings,
)

/** Пресет времени simple-настроек. */
enum class CroissantTime {
    /** Без таймера. */
    NONE,
    CLASSIC,
    RAPID,
    BLITZ,
}

/** Часы пресета [time]. */
data class CroissantTimePreset(
    val time: CroissantTime,
    val baseSeconds: Int,
    val bonusSeconds: Int,
)

/** Статичные данные игры для клиента: каталог типов тайлов ([GameRules.assets][site.qgames.games.core.GameRules.assets]). */
data class CroissantAssets(val tiles: List<CroissantTileType>)

/** Границы настроек для формы комнаты. */
data class CroissantSettingsLimits(
    val confectioners: IntRange,
    val timePresets: List<CroissantTimePreset>,
)

/**
 * Игровая часть состояния партии.
 *
 * [board] — выложенные тайлы (тип — код из [CroissantCatalog]), [spots] — связные участки сети,
 * [pending] — только что выложенный тайл, ждущий кондитера, [currentTile] — тайл, который сейчас выкладывает ходящий,
 * [deckPos] и [deckSize] — где мы в колоде. Порядок колоды в состояние не входит: он выводится из seed партии.
 */
data class CroissantGameState(
    val board: List<CroissantPlacedTile>,
    val spots: List<CroissantSpot>,
    val pending: CroissantPending?,
    val currentTile: String?,
    val deckPos: Int,
    val deckSize: Int,
)

/** Выложенный тайл: адресуется по id (`x:y`), [rotation] — в градусах, 0/90/180/270. */
data class CroissantPlacedTile(
    val id: String,
    val x: Int,
    val y: Int,
    val tile: String,
    val rotation: Int,
)

/**
 * Выложенный тайл, ждущий кондитера: [spots] — участки этого тайла, куда его можно поставить,
 * [completed] — участки, завершённые этим тайлом: считаются в конце хода, после шага кондитера, поэтому свободный из них
 * тоже есть в [spots].
 */
data class CroissantPending(
    val tileId: String,
    val spots: List<String>,
    val completed: List<String>,
)

/** Поля игры у игрока: накопленные «Золотые крошки» и непоставленные кондитеры. */
data class CroissantPlayer(
    val crumbs: Int,
    val confectioners: Int,
)

/** Ход — конверт платформы: `{type, data}`. Типы: `PLACE_TILE`, `PLACE_CONFECTIONER`, `SKIP_CONFECTIONER`. */
data class CroissantMove(
    val type: String,
    val data: CroissantMoveData? = null,
)

/** Параметры хода: клетка и поворот для тайла, участок для кондитера. */
data class CroissantMoveData(
    val x: Int? = null,
    val y: Int? = null,
    val rotation: Int? = null,
    val spotId: String? = null,
)

/** Тип участка сети. */
enum class CroissantSpotType {
    /** Пекарня: квартал с вывеской. */
    BAKERY,

    /** Мучная тропа: путь доставки. */
    TRAIL,

    /** Сыроварня при аббатстве. */
    DAIRY,

    /** Луг: стада пасутся, итоговый подсчёт только в конце. */
    PASTURE,
}

/** Участок на конкретном тайле: [index] — номер участка этого вида внутри тайла. */
data class CroissantSpotMember(val tileId: String, val index: Int)

/** Кондитер на участке: чей и на каком тайле. */
data class CroissantConfectioner(val playerId: java.util.UUID, val tileId: String)

/**
 * Связный участок сети: пекарни и тропы, объединённые через границы клеток, сыроварня (один тайл) и луга.
 *
 * [open] — сколько выходов участка ещё смотрит в пустые клетки (у завершённого 0; у луга не считается);
 * [signs] — вывески в пекарне; [complete] — участок завершён; [bakeries] — у луга: id примыкающих пекарен.
 */
data class CroissantSpot(
    val id: String,
    val type: CroissantSpotType,
    val members: List<CroissantSpotMember>,
    val open: Int,
    val signs: Int,
    val confectioners: List<CroissantConfectioner>,
    val complete: Boolean,
    val bakeries: List<String> = emptyList(),
)

/** Пресеты времени simple-настроек. */
val CroissantTimePresets = listOf(
    CroissantTimePreset(CroissantTime.CLASSIC, baseSeconds = 1800, bonusSeconds = 15),
    CroissantTimePreset(CroissantTime.RAPID, baseSeconds = 600, bonusSeconds = 5),
    CroissantTimePreset(CroissantTime.BLITZ, baseSeconds = 180, bonusSeconds = 2),
)

/** Границы числа кондитеров. */
val CroissantConfectionersRange = 5..10

/** Advanced-настройки по умолчанию: кондитерская классика, без часов. */
val CroissantDefaults = CroissantAdvancedSettings(
    confectioners = 7,
    timeControl = TimeControlSettings(
        enabled = false,
        baseSeconds = 1800,
        bonusType = TimeBonusType.INCREMENT,
        bonusSeconds = 15,
    ),
)
