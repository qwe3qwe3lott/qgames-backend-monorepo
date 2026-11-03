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
 * Тип тайла (их 14, а тайлов в колоде 72). Тайл — перечень элементов: пекарни, тропы, постройка в центре
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

/** Каталог тайлов: 14 типов, колода 72 тайла вместе со стартовым. */
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

    /** Сыроварня в открытом поле. */
    val DAIRY = CroissantTileType(
        code = "dairy",
        title = "Сыроварня",
        edges = edges(pastureEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        structures = CroissantStructures(dairy = true),
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    /** Сыроварня у мучной тропы: тропа входит с севера и заканчивается у ворот, луг огибает сыроварню целиком. */
    val DAIRY_TRAIL = CroissantTileType(
        code = "dairy-trail",
        title = "Сыроварня у тропы",
        edges = edges(trailEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        trails = listOf(CroissantTrailSpec(structure = true)),
        structures = CroissantStructures(dairy = true),
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    /** Пекарня на одной стороне. */
    val BAKERY_ONE = CroissantTileType(
        code = "bakery-one",
        title = "Пекарня",
        edges = edges(bakeryEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        pastures = listOf(pasture(E0, E1, S0, S1, W0, W1, adjacentBakeries = listOf(0))),
    )

    /** Пекарня на одной стороне с вывеской. */
    val BAKERY_ONE_SIGN = CroissantTileType(
        code = "bakery-one-sign",
        title = "Пекарня с вывеской",
        edges = edges(bakeryEdge(0), pastureEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(pasture(E0, E1, S0, S1, W0, W1, adjacentBakeries = listOf(0))),
    )

    /** Угловая пекарня на двух соседних сторонах. */
    val BAKERY_CORNER = CroissantTileType(
        code = "bakery-corner",
        title = "Угловая пекарня",
        edges = edges(bakeryEdge(0), bakeryEdge(0), pastureEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(pasture(S0, S1, W0, W1, adjacentBakeries = listOf(0))),
    )

    /** Прямая тропа, луга по обе стороны разделены. */
    val TRAIL_STRAIGHT = CroissantTileType(
        code = "trail-straight",
        title = "Прямая тропа",
        edges = edges(trailEdge(0), pastureEdge(1), trailEdge(0), pastureEdge(0)),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(pasture(N0, W0, W1, S0), pasture(N1, E0, E1, S1)),
    )

    /** Поворот тропы, внутренний и внешний луг разделены. */
    val TRAIL_CURVE = CroissantTileType(
        code = "trail-curve",
        title = "Изгиб тропы",
        edges = edges(trailEdge(0), pastureEdge(1), pastureEdge(1), trailEdge(0)),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(pasture(N0, W0), pasture(N1, E0, E1, S0, S1, W1)),
    )

    /** Сад, к которому сходятся три тропы: каждая заканчивается у сада, лугов три. */
    val TRAIL_CROSS = CroissantTileType(
        code = "trail-cross",
        title = "Развилка троп",
        edges = edges(trailEdge(0), trailEdge(1), trailEdge(2), pastureEdge(2)),
        trails = List(3) { CroissantTrailSpec(structure = true) },
        structures = CroissantStructures(garden = true),
        pastures = listOf(pasture(N1, E0), pasture(E1, S1), pasture(S0, W0, W1, N0)),
    )

    /** Сад, к которому сходятся четыре тропы: каждая заканчивается у сада, лугов четыре. */
    val TRAIL_JUNCTION = CroissantTileType(
        code = "trail-junction",
        title = "Перекрёсток троп",
        edges = edges(trailEdge(0), trailEdge(1), trailEdge(2), trailEdge(3)),
        trails = List(4) { CroissantTrailSpec(structure = true) },
        structures = CroissantStructures(garden = true),
        pastures = listOf(pasture(N0, W0), pasture(N1, E0), pasture(E1, S1), pasture(S0, W1)),
    )

    /** Пекарня и тропа напротив неё, тропа входит в пекарню; луг разделён тропой на две части. */
    val BAKERY_TRAIL = CroissantTileType(
        code = "bakery-trail",
        title = "Ворота пекарни",
        edges = edges(bakeryEdge(0), pastureEdge(1), trailEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec(bakeryIndex = 0)),
        pastures = listOf(
            pasture(W0, W1, S0, adjacentBakeries = listOf(0)),
            pasture(E0, E1, S1, adjacentBakeries = listOf(0)),
        ),
    )

    /** Пекарня и тропа-дуга с востока на юг мимо неё: внутренний угол луга отделён, к пекарне примыкает внешний луг. */
    val BAKERY_TRAIL_T = CroissantTileType(
        code = "bakery-trail-t",
        title = "Пекарня на распутье",
        edges = edges(bakeryEdge(0), trailEdge(0), trailEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(E0, S0, W0, W1, adjacentBakeries = listOf(0)),
            pasture(E1, S1),
        ),
    )

    /** Две пекарни напротив друг друга, тропа между ними. */
    val BAKERY_TWO = CroissantTileType(
        code = "bakery-two",
        title = "Две пекарни",
        edges = edges(bakeryEdge(0), trailEdge(0), bakeryEdge(1), trailEdge(0)),
        bakeries = listOf(CroissantBakerySpec(), CroissantBakerySpec()),
        trails = listOf(CroissantTrailSpec()),
        pastures = listOf(
            pasture(E0, W0, adjacentBakeries = listOf(0)),
            pasture(E1, W1, adjacentBakeries = listOf(1)),
        ),
    )

    /** Пекарня на трёх сторонах. */
    val BAKERY_THREE = CroissantTileType(
        code = "bakery-three",
        title = "Большая пекарня",
        edges = edges(bakeryEdge(0), bakeryEdge(0), bakeryEdge(0), pastureEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
        pastures = listOf(pasture(W0, W1, adjacentBakeries = listOf(0))),
    )

    /** Пекарня по всему периметру. */
    val BAKERY_FOUR = CroissantTileType(
        code = "bakery-four",
        title = "Пекарня-крепость",
        edges = edges(bakeryEdge(0), bakeryEdge(0), bakeryEdge(0), bakeryEdge(0)),
        bakeries = listOf(CroissantBakerySpec(sign = true)),
    )

    /** Все типы каталога: карточка игры отдаёт их клиенту целиком. */
    val TYPES: List<CroissantTileType> = listOf(
        DAIRY,
        DAIRY_TRAIL,
        BAKERY_ONE,
        BAKERY_ONE_SIGN,
        BAKERY_CORNER,
        TRAIL_STRAIGHT,
        TRAIL_CURVE,
        TRAIL_CROSS,
        TRAIL_JUNCTION,
        BAKERY_TRAIL,
        BAKERY_TRAIL_T,
        BAKERY_TWO,
        BAKERY_THREE,
        BAKERY_FOUR,
    )

    private val byCode: Map<String, CroissantTileType> = TYPES.associateBy { it.code }

    fun type(code: String): CroissantTileType =
        byCode[code] ?: error("Unknown tile type '$code', the catalog has ${byCode.keys}")

    /**
     * Состав колоды: сколько тайлов каждого типа. Набор рабочий — перед публикацией игры его сверяет пользователь
     * (состав коробки — вопрос баланса, а не правил). Сумма — 72 тайла вместе со стартовым.
     */
    val COUNTS: Map<String, Int> = linkedMapOf(
        DAIRY.code to 4,
        DAIRY_TRAIL.code to 2,
        BAKERY_ONE.code to 12,
        BAKERY_ONE_SIGN.code to 7,
        BAKERY_CORNER.code to 10,
        TRAIL_STRAIGHT.code to 8,
        TRAIL_CURVE.code to 9,
        TRAIL_CROSS.code to 4,
        TRAIL_JUNCTION.code to 1,
        BAKERY_TRAIL.code to 6,
        BAKERY_TRAIL_T.code to 4,
        BAKERY_TWO.code to 2,
        BAKERY_THREE.code to 2,
        BAKERY_FOUR.code to 1,
    )

    /** Базовая игра: состав коробки — [COUNTS]. Дополнения добавят свои модули. */
    val BASE_MODULE: CroissantModule = CroissantModule("base", COUNTS)

    /** Стартовый тайл партии: пекарня с тропой от ворот. */
    val START_TILE: String = BAKERY_TRAIL.code

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
