package site.qgames.games.croissant

import java.util.Random

/**
 * «Круассан» — игра про сеть пекарен в Долине Круассанов. Игроки выкладывают тайлы кварталов, тянут «Мучные тропы»
 * (пути доставки), растят «Пекарни» (кварталы с вывеской), открывают «Сыроварни» при аббатствах и пасут стада на лугах,
 * а за это получают «Золотые крошки» — очки.
 *
 * Правила кода — `games/CLAUDE.md`, устройство модели партии — `adr/c4/3-backend-games.md`. Модуль чистый: без Spring,
 * БД и IO. Случайность — только из seed партии (порядок колоды).
 *
 * Термины игры (и только они) в коде и контракте: [CroissantEdge.BAKERY] — пекарня, [CroissantEdge.TRAIL] — мучная
 * тропа, [CroissantEdge.PASTURE] — луг, постройки — `structures`: сыроварня — `dairy`, сад — `garden`, «Золотые крошки» — `crumbs`, фишки игроков —
 * `confectioners`.
 */

/** Сторона тайла. Координаты клеток: [CroissantDirection.E] — +x, [CroissantDirection.N] — +y. */
enum class CroissantDirection {
    N,
    E,
    S,
    W,
    ;

    val opposite: CroissantDirection
        get() = when (this) {
            N -> S
            E -> W
            S -> N
            W -> E
        }

    /** Смещение клетки по стороне. */
    val dx: Int get() = when (this) { E -> 1; W -> -1; else -> 0 }

    /** Смещение клетки по стороне. */
    val dy: Int get() = when (this) { N -> 1; S -> -1; else -> 0 }
}

/** Чем занята сторона тайла. */
enum class CroissantEdge {
    /** Пекарня: сторона входит в квартал с вывеской. */
    BAKERY,

    /** Мучная тропа: путь доставки выходит на эту сторону. */
    TRAIL,

    /** Луг: свободная сторона, по ней пасутся стада. */
    PASTURE,
}

/**
 * Сторона тайла: какой элемент на неё выходит — тип [type] и [index] в его списке тайла ([CroissantTileType.bakeries],
 * [CroissantTileType.trails] или [CroissantTileType.pastures]).
 */
data class CroissantEdgeSpec(val type: CroissantEdge, val index: Int)

/** Стороны тайла N, E, S, W — единственный источник того, какие стороны занимают пекарни, тропы и луга. */
data class CroissantTileEdges(
    val n: CroissantEdgeSpec,
    val e: CroissantEdgeSpec,
    val s: CroissantEdgeSpec,
    val w: CroissantEdgeSpec,
) {
    operator fun get(dir: CroissantDirection): CroissantEdgeSpec = when (dir) {
        CroissantDirection.N -> n
        CroissantDirection.E -> e
        CroissantDirection.S -> s
        CroissantDirection.W -> w
    }
}

/**
 * Полуребро — половина стороны тайла: луга делят сторону пополам.
 *
 * [half] — абсолютная половина: у N и S 0 — западная, 1 — восточная; у E и W 0 — северная, 1 — южная. Полуребра,
 * смотрящие друг на друга через границу клеток, имеют одинаковые `dir.opposite` и **одинаковый** [half], поэтому пара
 * находится без дополнительных соглашений о повороте.
 */
data class CroissantHalfEdge(val dir: CroissantDirection, val half: Int)

/** Пекарня на тайле: её стороны — рёбра [CroissantTileType.edges] с её индексом; [sign] — вывеска. */
data class CroissantBakerySpec(val sign: Boolean = false)

/**
 * Мучная тропа на тайле: её стороны — рёбра [CroissantTileType.edges] с её индексом. Тропа на две стороны и больше
 * соединяет эти стороны и цели не имеет. Тропа на одну сторону входит ровно в одну цель и там заканчивается: в пекарню
 * [bakeryIndex] (индекс в [CroissantTileType.bakeries]) или в постройку тайла ([structure], [CroissantTileType.structures]).
 */
data class CroissantTrailSpec(val bakeryIndex: Int? = null, val structure: Boolean = false) {
    /** Сколько целей у тропы. */
    val targets: Int get() = (if (bakeryIndex != null) 1 else 0) + (if (structure) 1 else 0)
}

/**
 * Постройка в центре тайла: сыроварня ([dairy]) или сад ([garden]), не обе сразу. Сыроварня — участок для кондитера,
 * сад — не участок: кондитера на него не ставят, он только обрывает тропы. Луга постройки не делят.
 */
data class CroissantStructures(val garden: Boolean = false, val dairy: Boolean = false) {
    /** Есть ли на тайле постройка. */
    val any: Boolean get() = garden || dairy
}

/**
 * Луг на тайле: полуребра, которыми участок выходит к соседним клеткам, и пекарни этого тайла, которых касается этот
 * кусок луга ([adjacentBakeries], индексы в [CroissantTileType.bakeries]). Смежность задаётся по куску на тайле: сеть
 * склеивает куски одного луга по всем тайлам и собирает все пекарни, которых касается луг целиком (`CroissantNetwork`).
 */
data class CroissantPastureSpec(val halves: List<CroissantHalfEdge>, val adjacentBakeries: List<Int> = emptyList())

/**
 * Тип тайла (их 24, а тайлов в колоде 72). Тайл — перечень элементов: пекарни, тропы, постройка в центре
 * ([structures]) и луга. [edges] — что выходит на каждую сторону: на одной стороне строго один элемент — пекарня,
 * тропа или луг, и стороны пекарен и троп задаются только здесь.
 *
 * Каталог — единственный источник геометрии и на сервере, и на клиенте: карточка игры отдаёт его целиком
 * ([CroissantGameRules.assets]), состояние партии ссылается на тип по [code], поэтому фронт не дублирует тайлы и не
 * расходится с правилами.
 */
data class CroissantTileType(
    val code: String,
    /** Подпись тайла для интерфейса. */
    val title: String,
    val edges: CroissantTileEdges,
    val bakeries: List<CroissantBakerySpec> = emptyList(),
    val trails: List<CroissantTrailSpec> = emptyList(),
    val structures: CroissantStructures = CroissantStructures(),
    val pastures: List<CroissantPastureSpec> = emptyList(),
) {
    init {
        CroissantDirection.entries.forEach { dir ->
            val edge = edges[dir]
            val size = when (edge.type) {
                CroissantEdge.BAKERY -> bakeries.size
                CroissantEdge.TRAIL -> trails.size
                CroissantEdge.PASTURE -> pastures.size
            }
            require(edge.index in 0 until size) { "Tile '$code': side $dir leads to a missing ${edge.type} ${edge.index}" }
        }
        bakeries.indices.forEach { index ->
            require(bakerySides(index).isNotEmpty()) { "Tile '$code': bakery $index has no sides" }
        }
        require(!(structures.dairy && structures.garden)) { "Tile '$code': the dairy and the garden both take the center" }
        trails.forEachIndexed { index, trail ->
            val sides = trailSides(index)
            require(sides.isNotEmpty()) { "Tile '$code': trail $index has no sides" }
            if (sides.size == 1) {
                require(trail.targets == 1) { "Tile '$code': trail $index has one side and needs exactly one target" }
            } else {
                require(trail.targets == 0) { "Tile '$code': trail $index joins sides and can't have a target" }
            }
            require(trail.bakeryIndex == null || trail.bakeryIndex in bakeries.indices) {
                "Tile '$code': trail $index leads to a missing bakery ${trail.bakeryIndex}"
            }
            require(!trail.structure || structures.any) { "Tile '$code': trail $index leads to a missing structure" }
        }
        val halves = pastures.flatMap { it.halves }
        val free = CroissantDirection.entries.filter { edge(it) != CroissantEdge.BAKERY }
            .flatMap { listOf(CroissantHalfEdge(it, 0), CroissantHalfEdge(it, 1)) }
        require(halves.size == halves.toSet().size && halves.toSet() == free.toSet()) {
            "Tile '$code': pastures must cover every half-edge of the sides without bakeries exactly once"
        }
        CroissantDirection.entries.filter { edge(it) == CroissantEdge.PASTURE }.forEach { dir ->
            val index = edges[dir].index
            require(pastureAt(CroissantHalfEdge(dir, 0)) == index && pastureAt(CroissantHalfEdge(dir, 1)) == index) {
                "Tile '$code': both half-edges of the pasture side $dir must belong to pasture $index"
            }
        }
        pastures.forEachIndexed { index, pasture ->
            require(pasture.adjacentBakeries.all { it in bakeries.indices }) {
                "Tile '$code': pasture $index touches a missing bakery"
            }
        }
    }

    fun edge(dir: CroissantDirection): CroissantEdge = edges[dir].type

    /** Стороны пекарни [index]. */
    fun bakerySides(index: Int): List<CroissantDirection> = sidesOf(CroissantEdge.BAKERY, index)

    /** Стороны тропы [index]. */
    fun trailSides(index: Int): List<CroissantDirection> = sidesOf(CroissantEdge.TRAIL, index)

    /** Индекс пекарни, занимающей сторону [dir]; null — пекарни на этой стороне нет. */
    fun bakeryAt(dir: CroissantDirection): Int? = edges[dir].takeIf { it.type == CroissantEdge.BAKERY }?.index

    /** Индекс тропы, выходящей на сторону [dir]; null — тропы нет. */
    fun trailAt(dir: CroissantDirection): Int? = edges[dir].takeIf { it.type == CroissantEdge.TRAIL }?.index

    /** Индекс луга, которому принадлежит полуребро [half]; null — полуребро занято пекарней. */
    fun pastureAt(half: CroissantHalfEdge): Int? = pastures.indexOfFirst { half in it.halves }.takeIf { it >= 0 }

    private fun sidesOf(type: CroissantEdge, index: Int): List<CroissantDirection> =
        CroissantDirection.entries.filter { edges[it].type == type && edges[it].index == index }
}

/**
 * Геометрия тайла после поворота: участки каталога в абсолютных сторонах клетки. Каталог описан в базовой
 * ориентации, поэтому абсолютная сторона переводится в локальную обратным поворотом.
 */
class CroissantShape(val type: CroissantTileType, val rotation: Int) {

    private val turn = Math.floorMod(rotation / 90, 4)

    /** Абсолютная сторона локальной стороны [local]. */
    fun dir(local: CroissantDirection): CroissantDirection =
        CroissantDirection.entries[(local.ordinal + turn) % 4]

    /** Локальная сторона абсолютной стороны [absolute]. */
    fun localDir(absolute: CroissantDirection): CroissantDirection =
        CroissantDirection.entries[Math.floorMod(absolute.ordinal - turn, 4)]

    /** Абсолютное полуребро локального полуребра [local]. */
    fun half(local: CroissantHalfEdge): CroissantHalfEdge {
        val dir = dir(local.dir)
        val point = CroissantGeometry.rotateCorner(CroissantGeometry.corner(local), turn)
        return CroissantHalfEdge(dir, CroissantGeometry.halfOf(dir, point))
    }

    /** Чем занята абсолютная сторона [absolute]. */
    fun edge(absolute: CroissantDirection): CroissantEdge = type.edge(localDir(absolute))

    /** Абсолютные стороны пекарни [index]. */
    fun bakerySides(index: Int): List<CroissantDirection> = type.bakerySides(index).map(::dir)

    /** Абсолютные стороны тропы [index]. */
    fun trailSides(index: Int): List<CroissantDirection> = type.trailSides(index).map(::dir)

    /** Абсолютные полуребра луга [index]. */
    fun pastureHalves(index: Int): List<CroissantHalfEdge> = type.pastures[index].halves.map(::half)

    /** Сколько лугов у тайла. */
    fun pastureCount(): Int = type.pastures.size

    /** Пекарня на абсолютной стороне [absolute]; null — её там нет. */
    fun bakeryAt(absolute: CroissantDirection): Int? = type.bakeryAt(localDir(absolute))

    /** Тропа на абсолютной стороне [absolute]; null — её там нет. */
    fun trailAt(absolute: CroissantDirection): Int? = type.trailAt(localDir(absolute))

    /** Луг, которому принадлежит абсолютное полуребро [half]; null — полуребро занято пекарней. */
    fun pastureAt(half: CroissantHalfEdge): Int? {
        val localDir = localDir(half.dir)
        val point = CroissantGeometry.rotateCorner(CroissantGeometry.corner(half), Math.floorMod(4 - turn, 4))
        return type.pastureAt(CroissantHalfEdge(localDir, CroissantGeometry.halfOf(localDir, point)))
    }
}

/** Угол клетки, которым адресуется полуребро: поворот тайла — поворот угла. */
object CroissantGeometry {

    /** Угол полуребра: (-1|1, -1|1). Полуребра одной стороны делят по углу. */
    fun corner(half: CroissantHalfEdge): Pair<Int, Int> = when (half.dir) {
        CroissantDirection.N -> Pair(if (half.half == 0) -1 else 1, 1)
        CroissantDirection.S -> Pair(if (half.half == 0) -1 else 1, -1)
        CroissantDirection.E -> Pair(1, if (half.half == 0) 1 else -1)
        CroissantDirection.W -> Pair(-1, if (half.half == 0) 1 else -1)
    }

    /** Половина стороны [dir], которой принадлежит угол [corner]. */
    fun halfOf(dir: CroissantDirection, corner: Pair<Int, Int>): Int =
        if (corner(CroissantHalfEdge(dir, 0)) == corner) 0 else 1

    /** Поворот угла по часовой стрелке [turns] раз. */
    fun rotateCorner(corner: Pair<Int, Int>, turns: Int): Pair<Int, Int> {
        var (x, y) = corner
        repeat(Math.floorMod(turns, 4)) {
            val nx = y
            val ny = -x
            x = nx
            y = ny
        }
        return Pair(x, y)
    }
}

/** Каталог тайлов: 24 типа, колода 72 тайла вместе со стартовым. */
object CroissantCatalog {

    private val N0 = CroissantHalfEdge(CroissantDirection.N, 0)
    private val N1 = CroissantHalfEdge(CroissantDirection.N, 1)
    private val E0 = CroissantHalfEdge(CroissantDirection.E, 0)
    private val E1 = CroissantHalfEdge(CroissantDirection.E, 1)
    private val S0 = CroissantHalfEdge(CroissantDirection.S, 0)
    private val S1 = CroissantHalfEdge(CroissantDirection.S, 1)
    private val W0 = CroissantHalfEdge(CroissantDirection.W, 0)
    private val W1 = CroissantHalfEdge(CroissantDirection.W, 1)
    private val ALL_HALVES = listOf(N0, N1, E0, E1, S0, S1, W0, W1)

    private fun pasture(vararg halves: CroissantHalfEdge, adjacentBakeries: List<Int> = emptyList()) =
        CroissantPastureSpec(halves.toList(), adjacentBakeries)

    private fun bakeryEdge(index: Int) = CroissantEdgeSpec(CroissantEdge.BAKERY, index)

    private fun trailEdge(index: Int) = CroissantEdgeSpec(CroissantEdge.TRAIL, index)

    private fun pastureEdge(index: Int) = CroissantEdgeSpec(CroissantEdge.PASTURE, index)

    private fun edges(n: CroissantEdgeSpec, e: CroissantEdgeSpec, s: CroissantEdgeSpec, w: CroissantEdgeSpec) =
        CroissantTileEdges(n, e, s, w)

    val TRAIL_TO_DAIRY = CroissantTileType(
        code = "trail-to-dairy",
        title = "Сыроварня у тропы",
        edges = edges(pastureEdge(0), pastureEdge(0), trailEdge(0), pastureEdge(0)),
        trails = listOf(CroissantTrailSpec(structure = true)),
        structures = CroissantStructures(dairy = true),
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    val DAIRY = CroissantTileType(
        code = "dairy",
        title = "Сыроварня",
        edges = edges(pastureEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        structures = CroissantStructures(dairy = true),
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    val BAKERY_CENTER_WITH_SIGN = CroissantTileType(
        code = "bakery-center-with-sign",
        title = "Центр пекарни с вывеской",
        edges = edges(bakeryEdge(0), bakeryEdge(0), bakeryEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
    )

    val BAKERY_AND_TRAIL_NEAR_BY = CroissantTileType(
        code = "bakery-and-trail-near-by",
        title = "Тропа рядом с пекарней",
        edges = edges(bakeryEdge(0), trailEdge(0), pastureEdge(1), trailEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(E0, W0, adjacentBakeries = listOf(0)),
            pasture(E1, S0, S1, W1),
        ),
    )

    val BAKERY = CroissantTileType(
        code = "bakery",
        title = "Пекарня",
        edges = edges(bakeryEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        pastures = listOf(
            pasture(E0, E1, S0, S1, W0, W1, adjacentBakeries = listOf(0)),
        ),
    )

    val BAKERY_BRIDGE_WITH_SIGN = CroissantTileType(
        code = "bakery-bridge-with-sign",
        title = "Узкая пекарня с вывеской",
        edges = edges(pastureEdge(0), bakeryEdge(0), pastureEdge(1), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(
            pasture(N0, N1, adjacentBakeries = listOf(0)),
            pasture(S0, S1, adjacentBakeries = listOf(0)),
        ),
    )

    val BAKERY_BRIDGE = CroissantTileType(
        code = "bakery-bridge",
        title = "Узкая пекарня",
        edges = edges(pastureEdge(0), bakeryEdge(0), pastureEdge(1), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        pastures = listOf(
            pasture(N0, N1, adjacentBakeries = listOf(0)),
            pasture(S0, S1, adjacentBakeries = listOf(0)),
        ),
    )

    val TWO_OPPOSITE_BAKERIES = CroissantTileType(
        code = "two-opposite-bakeries",
        title = "Две пекарни напротив",
        edges = edges(bakeryEdge(0), pastureEdge(0), bakeryEdge(1), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec(), CroissantBakerySpec()),
        pastures = listOf(
            pasture(E0, E1, W0, W1, adjacentBakeries = listOf(0, 1)),
        ),
    )

    val TWO_NEIGHBOUR_BAKERIES = CroissantTileType(
        code = "two-neighbour-bakeries",
        title = "Две пекарни рядом",
        edges = edges(bakeryEdge(0), pastureEdge(0), pastureEdge(0), bakeryEdge(1)),
        bakeries = listOf(CroissantBakerySpec(), CroissantBakerySpec()),
        pastures = listOf(
            pasture(S0, S1, E0, E1, adjacentBakeries = listOf(0, 1)),
        ),
    )

    val BAKERY_AND_TRAIL_TURN_NEAR_BY = CroissantTileType(
        code = "bakery-and-trail-turn-near-by",
        title = "Поворот тропы рядом с пекарней",
        edges = edges(bakeryEdge(0), trailEdge(0), trailEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(W0, W1, S0, E0, adjacentBakeries = listOf(0)),
            pasture(S1, E1),
        ),
    )

    val BAKERY_AND_TRAIL_TURN_NEAR_BY_2 = CroissantTileType(
        code = "bakery-and-trail-turn-near-by-2",
        title = "Поворот тропы рядом с пекарней",
        edges = edges(bakeryEdge(0), pastureEdge(0), trailEdge(0), trailEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(W0, S1, E0, E1, adjacentBakeries = listOf(0)),
            pasture(S0, W1),
        ),
    )

    val BAKERY_AND_TRAIL_FORK_INTO_GARDEN_NEAR_BY = CroissantTileType(
        code = "bakery-and-trail-fork-into-garden-near-by",
        title = "Развилка тропы с садом рядом с пекарней",
        edges = edges(bakeryEdge(0), trailEdge(0), trailEdge(1), trailEdge(2)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = List(3) { CroissantTrailSpec(structure = true) },
        structures = CroissantStructures(garden = true),
        pastures = listOf(
            pasture(W0, E0, adjacentBakeries = listOf(0)),
            pasture(W1, S0),
            pasture(S1, E1),
        ),
    )

    val LARGE_BAKERY_WITH_SIGN = CroissantTileType(
        code = "large-bakery-with-sign",
        title = "Большая пекарня с вывеской",
        edges = edges(bakeryEdge(0), bakeryEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(
            pasture(S0, S1, W0, W1, adjacentBakeries = listOf(0)),
        ),
    )

    val LARGE_BAKERY = CroissantTileType(
        code = "large-bakery",
        title = "Большая пекарня",
        edges = edges(bakeryEdge(0), bakeryEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        pastures = listOf(
            pasture(S0, S1, W0, W1, adjacentBakeries = listOf(0)),
        ),
    )

    val LARGE_BAKERY_WITH_SIGN_AND_TRAIL_TURN_NEAR_BY = CroissantTileType(
        code = "large-bakery-with-sign-and-trail-turn-near-by",
        title = "Поворот тропы рядом с большой пекарней с вывеской",
        edges = edges(bakeryEdge(0), trailEdge(0), trailEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(S0, E0, adjacentBakeries = listOf(0)),
            pasture(S1, E1),
        ),
    )

    val LARGE_BAKERY_AND_TRAIL_TURN_NEAR_BY = CroissantTileType(
        code = "large-bakery-and-trail-turn-near-by",
        title = "Поворот тропы рядом с большой пекарней",
        edges = edges(bakeryEdge(0), trailEdge(0), trailEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(S0, E0, adjacentBakeries = listOf(0)),
            pasture(S1, E1),
        ),
    )

    val HUGE_BAKERY_WITH_SIGN = CroissantTileType(
        code = "huge-bakery-with-sign",
        title = "Огромная пекарня с вывеской",
        edges = edges(bakeryEdge(0), bakeryEdge(0), pastureEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(
            pasture(S0, S1, adjacentBakeries = listOf(0)),
        ),
    )

    val HUGE_BAKERY = CroissantTileType(
        code = "huge-bakery",
        title = "Огромная пекарня",
        edges = edges(bakeryEdge(0), bakeryEdge(0), pastureEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        pastures = listOf(
            pasture(S0, S1, adjacentBakeries = listOf(0)),
        ),
    )

    val HUGE_BAKERY_WITH_SIGN_AND_TRAIL = CroissantTileType(
        code = "huge-bakery-with-sign-and-trail",
        title = "Огромная пекарня с вывеской и тропой",
        edges = edges(bakeryEdge(0), bakeryEdge(0), trailEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        trails = listOf(CroissantTrailSpec(bakeryIndex = 0)),
        pastures = listOf(
            pasture(S0, adjacentBakeries = listOf(0)),
            pasture(S1, adjacentBakeries = listOf(0)),
        ),
    )

    val HUGE_BAKERY_WITH_TRAIL = CroissantTileType(
        code = "huge-bakery-with-trail",
        title = "Огромная пекарня с тропой",
        edges = edges(bakeryEdge(0), bakeryEdge(0), trailEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec(bakeryIndex = 0)),
        pastures = listOf(
            pasture(S0, adjacentBakeries = listOf(0)),
            pasture(S1, adjacentBakeries = listOf(0)),
        ),
    )

    val TRAIL = CroissantTileType(
        code = "trail",
        title = "Тропа",
        edges = edges(trailEdge(0), pastureEdge(1), trailEdge(0), pastureEdge(0)),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(N0, W0, W1, S0),
            pasture(N1, E0, E1, S1),
        ),
    )

    val TRAIL_TURN = CroissantTileType(
        code = "trail-turn",
        title = "Поворот тропы",
        edges = edges(pastureEdge(1), pastureEdge(1), trailEdge(0), trailEdge(0)),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(W1, S0),
            pasture(W0, N0, N1, E0, E1, S1),
        ),
    )

    val TRAIL_FORK_WITH_GARDEN = CroissantTileType(
        code = "trail-fork-with-garden",
        title = "Развилка тропы с садом",
        edges = edges(pastureEdge(0), trailEdge(0), trailEdge(1), trailEdge(2)),
        trails = List(3) { CroissantTrailSpec(structure = true) },
        structures = CroissantStructures(garden = true),
        pastures = listOf(
            pasture(W0, N0, N1, E0),
            pasture(W1, S0),
            pasture(S1, E1),
        ),
    )

    val TRAIL_CROSS_WITH_GARDEN = CroissantTileType(
        code = "trail-cross-with-garden",
        title = "Перекрёсток троп с садом",
        edges = edges(trailEdge(0), trailEdge(1), trailEdge(2), trailEdge(3)),
        trails = List(4) { CroissantTrailSpec(structure = true) },
        structures = CroissantStructures(garden = true),
        pastures = listOf(
            pasture(W0, N0),
            pasture(N1, E0),
            pasture(E1, S1),
            pasture(S0, W1),
        ),
    )

    /** Все типы каталога: карточка игры отдаёт их клиенту целиком. */
    val TYPES: List<CroissantTileType> = listOf(
        TRAIL_TO_DAIRY,
        DAIRY,
        BAKERY_CENTER_WITH_SIGN,
        BAKERY_AND_TRAIL_NEAR_BY,
        BAKERY,
        BAKERY_BRIDGE_WITH_SIGN,
        BAKERY_BRIDGE,
        TWO_OPPOSITE_BAKERIES,
        TWO_NEIGHBOUR_BAKERIES,
        BAKERY_AND_TRAIL_TURN_NEAR_BY,
        BAKERY_AND_TRAIL_TURN_NEAR_BY_2,
        BAKERY_AND_TRAIL_FORK_INTO_GARDEN_NEAR_BY,
        LARGE_BAKERY_WITH_SIGN,
        LARGE_BAKERY,
        LARGE_BAKERY_WITH_SIGN_AND_TRAIL_TURN_NEAR_BY,
        LARGE_BAKERY_AND_TRAIL_TURN_NEAR_BY,
        HUGE_BAKERY_WITH_SIGN,
        HUGE_BAKERY,
        HUGE_BAKERY_WITH_SIGN_AND_TRAIL,
        HUGE_BAKERY_WITH_TRAIL,
        TRAIL,
        TRAIL_TURN,
        TRAIL_FORK_WITH_GARDEN,
        TRAIL_CROSS_WITH_GARDEN,
    )

    private val byCode: Map<String, CroissantTileType> = TYPES.associateBy { it.code }

    fun type(code: String): CroissantTileType =
        byCode[code] ?: error("Unknown tile type '$code', the catalog has ${byCode.keys}")

    /**
     * Состав колоды: сколько тайлов каждого типа. Набор рабочий — перед публикацией игры его сверяет пользователь
     * (состав коробки — вопрос баланса, а не правил). Сумма — 72 тайла вместе со стартовым.
     */
    val COUNTS: Map<String, Int> = linkedMapOf(
        TRAIL_TO_DAIRY.code to 2,
        DAIRY.code to 4,
        BAKERY_CENTER_WITH_SIGN.code to 1,
        BAKERY_AND_TRAIL_NEAR_BY.code to 4,
        BAKERY.code to 5,
        BAKERY_BRIDGE_WITH_SIGN.code to 2,
        BAKERY_BRIDGE.code to 1,
        TWO_OPPOSITE_BAKERIES.code to 3,
        TWO_NEIGHBOUR_BAKERIES.code to 2,
        BAKERY_AND_TRAIL_TURN_NEAR_BY.code to 3,
        BAKERY_AND_TRAIL_TURN_NEAR_BY_2.code to 3,
        BAKERY_AND_TRAIL_FORK_INTO_GARDEN_NEAR_BY.code to 3,
        LARGE_BAKERY_WITH_SIGN.code to 2,
        LARGE_BAKERY.code to 3,
        LARGE_BAKERY_WITH_SIGN_AND_TRAIL_TURN_NEAR_BY.code to 2,
        LARGE_BAKERY_AND_TRAIL_TURN_NEAR_BY.code to 3,
        HUGE_BAKERY_WITH_SIGN.code to 1,
        HUGE_BAKERY.code to 3,
        HUGE_BAKERY_WITH_SIGN_AND_TRAIL.code to 2,
        HUGE_BAKERY_WITH_TRAIL.code to 1,
        TRAIL.code to 8,
        TRAIL_TURN.code to 9,
        TRAIL_FORK_WITH_GARDEN.code to 4,
        TRAIL_CROSS_WITH_GARDEN.code to 1,
    )

    /** Базовая игра: состав коробки — [COUNTS]. Дополнения добавят свои модули. */
    val BASE_MODULE: CroissantModule = CroissantModule("base", COUNTS)

    /** Стартовый тайл партии. */
    val START_TILE: String = BAKERY_AND_TRAIL_NEAR_BY.code

    /**
     * Порядок тайлов партии из seed. Стартовый тайл уже на поле и в колоду не входит: в ней 71 тайл, экземпляры
     * [START_TILE] уменьшены на один.
     *
     * [returns] — моменты возврата невыложенного тайла (`deckPos` на тот момент, по порядку). Каждый возврат кладёт тайл
     * `deck[pos - 1]` ещё раз в оставшуюся часть колоды — в место, выведенное из seed, позиции и номера возврата. Так
     * колода целиком восстанавливается из seed и списка позиций, а её порядок в состояние не попадает.
     */
    fun deck(seed: Long, returns: List<Int> = emptyList()): List<String> {
        val tiles = ArrayList<String>(72 + returns.size)
        COUNTS.forEach { (code, count) -> repeat(count) { tiles += code } }
        tiles.remove(START_TILE)
        tiles.shuffle(Random(seed))
        returns.forEachIndexed { index, pos ->
            val tile = tiles[pos - 1]
            val offset = Random(seed * RETURN_SALT + pos * RETURN_SALT + index).nextInt(tiles.size - pos + 1)
            tiles.add(pos + offset, tile)
        }
        return tiles
    }

    /** Нечётный множитель: разводит случайность возвратов с порядком колоды и между собой. */
    private const val RETURN_SALT = 31L

    /** Размер колоды без стартового тайла. */
    val DECK_SIZE: Int = COUNTS.values.sum() - 1
}
