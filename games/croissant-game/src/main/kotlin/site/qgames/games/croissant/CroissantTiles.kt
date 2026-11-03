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
 * тропа, [CroissantEdge.PASTURE] — луг, сыроварня — `dairy`, сад — `garden`, «Золотые крошки» — `crumbs`, фишки игроков —
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
 * Полуребро — половина стороны тайла: луга делят сторону пополам.
 *
 * [half] — абсолютная половина: у N и S 0 — западная, 1 — восточная; у E и W 0 — северная, 1 — южная. Полуребра,
 * смотрящие друг на друга через границу клеток, имеют одинаковые `dir.opposite` и **одинаковый** [half], поэтому пара
 * находится без дополнительных соглашений о повороте.
 */
data class CroissantHalfEdge(val dir: CroissantDirection, val half: Int)

/** Пекарня на тайле: стороны квартала и вывеска. */
data class CroissantBakerySpec(val sides: List<CroissantDirection>, val sign: Boolean = false)

/**
 * Мучная тропа на тайле: стороны, на которые она выходит, и элемент тайла, в который она входит. Тропа на две стороны и
 * больше соединяет эти стороны и цели не имеет. Тропа на одну сторону входит ровно в одну цель: пекарню [bakery]
 * (индекс в [CroissantTileType.bakeries]), сыроварню ([dairy]) или сад ([garden]) — там тропа и заканчивается.
 */
data class CroissantTrailSpec(
    val sides: List<CroissantDirection>,
    val bakery: Int? = null,
    val dairy: Boolean = false,
    val garden: Boolean = false,
) {
    /** Сколько целей у тропы. */
    val targets: Int get() = (if (bakery != null) 1 else 0) + (if (dairy) 1 else 0) + (if (garden) 1 else 0)
}

/**
 * Луг на тайле: полуребра, которыми участок выходит к соседним клеткам, и индексы своих пекарен ([bakeries]) — по ним
 * итоговый подсчёт считает, к каким завершённым пекарням примыкает луг.
 */
data class CroissantPastureSpec(val halves: List<CroissantHalfEdge>, val bakeries: List<Int> = emptyList())

/**
 * Тип тайла (их 14, а тайлов в колоде 72). [edges] — по индексу [CroissantDirection.ordinal]: N, E, S, W. Тайл — перечень
 * элементов: пекарни, тропы, сыроварня или сад в центре и луга. На одной стороне — строго один элемент: пекарня, тропа
 * или луг. Сад — не участок: кондитера на него не ставят, он только обрывает тропы.
 *
 * Каталог — единственный источник геометрии и на сервере, и на клиенте: карточка игры отдаёт его целиком
 * ([CroissantGameRules.assets]), состояние партии ссылается на тип по [code], поэтому фронт не дублирует тайлы и не
 * расходится с правилами.
 */
data class CroissantTileType(
    val code: String,
    /** Подпись тайла для интерфейса. */
    val title: String,
    val edges: List<CroissantEdge>,
    val bakeries: List<CroissantBakerySpec> = emptyList(),
    val trails: List<CroissantTrailSpec> = emptyList(),
    val dairy: Boolean = false,
    val garden: Boolean = false,
    val pastures: List<CroissantPastureSpec> = emptyList(),
) {
    init {
        val bakerySides = bakeries.flatMap { it.sides }
        val trailSides = trails.flatMap { it.sides }
        require(bakerySides.size == bakerySides.toSet().size) { "Tile '$code': a side belongs to two bakeries" }
        require(trailSides.size == trailSides.toSet().size) { "Tile '$code': a side has two trails" }
        require(bakerySides.none { it in trailSides }) { "Tile '$code': a side has both a bakery and a trail" }
        require(!(dairy && garden)) { "Tile '$code': the dairy and the garden both take the center" }
        trails.forEachIndexed { index, trail ->
            require(trail.sides.isNotEmpty()) { "Tile '$code': trail $index has no sides" }
            if (trail.sides.size == 1) {
                require(trail.targets == 1) { "Tile '$code': trail $index has one side and needs exactly one target" }
            } else {
                require(trail.targets == 0) { "Tile '$code': trail $index joins sides and can't have a target" }
            }
            require(trail.bakery == null || trail.bakery in bakeries.indices) {
                "Tile '$code': trail $index leads to a missing bakery ${trail.bakery}"
            }
            require(!trail.dairy || dairy) { "Tile '$code': trail $index leads to a missing dairy" }
            require(!trail.garden || garden) { "Tile '$code': trail $index leads to a missing garden" }
        }
        val halves = pastures.flatMap { it.halves }
        val free = CroissantDirection.entries.filter { it !in bakerySides }
            .flatMap { listOf(CroissantHalfEdge(it, 0), CroissantHalfEdge(it, 1)) }
        require(halves.size == halves.toSet().size && halves.toSet() == free.toSet()) {
            "Tile '$code': pastures must cover every half-edge of the sides without bakeries exactly once"
        }
        pastures.forEachIndexed { index, pasture ->
            require(pasture.bakeries.all { it in bakeries.indices }) { "Tile '$code': pasture $index has a missing bakery" }
        }
    }

    fun edge(dir: CroissantDirection): CroissantEdge = edges[dir.ordinal]

    /** Индекс пекарни, занимающей сторону [dir]; null — пекарни на этой стороне нет. */
    fun bakeryAt(dir: CroissantDirection): Int? = bakeries.indexOfFirst { dir in it.sides }.takeIf { it >= 0 }

    /** Индекс тропы, выходящей на сторону [dir]; null — тропы нет. */
    fun trailAt(dir: CroissantDirection): Int? = trails.indexOfFirst { dir in it.sides }.takeIf { it >= 0 }

    /** Индекс луга, которому принадлежит полуребро [half]; null — полуребро занято пекарней или тропой. */
    fun pastureAt(half: CroissantHalfEdge): Int? = pastures.indexOfFirst { half in it.halves }.takeIf { it >= 0 }
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
    fun bakerySides(index: Int): List<CroissantDirection> = type.bakeries[index].sides.map(::dir)

    /** Абсолютные стороны тропы [index]. */
    fun trailSides(index: Int): List<CroissantDirection> = type.trails[index].sides.map(::dir)

    /** Абсолютные полуребра луга [index]. */
    fun pastureHalves(index: Int): List<CroissantHalfEdge> = type.pastures[index].halves.map(::half)

    /** Сколько лугов у тайла. */
    fun pastureCount(): Int = type.pastures.size

    /** Пекарня на абсолютной стороне [absolute]; null — её там нет. */
    fun bakeryAt(absolute: CroissantDirection): Int? = type.bakeryAt(localDir(absolute))

    /** Тропа на абсолютной стороне [absolute]; null — её там нет. */
    fun trailAt(absolute: CroissantDirection): Int? = type.trailAt(localDir(absolute))

    /** Луг, которому принадлежит абсолютное полуребро [half]; null — полуребро занято пекарней или тропой. */
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

    private fun pasture(vararg halves: CroissantHalfEdge, bakeries: List<Int> = emptyList()) =
        CroissantPastureSpec(halves.toList(), bakeries)

    /** Сыроварня в открытом поле. */
    val DAIRY = tile(
        code = "dairy",
        title = "Сыроварня",
        dairy = true,
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    /** Сыроварня у мучной тропы: тропа входит с севера и заканчивается у ворот, луг огибает сыроварню целиком. */
    val DAIRY_TRAIL = tile(
        code = "dairy-trail",
        title = "Сыроварня у тропы",
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.N), dairy = true)),
        dairy = true,
        pastures = listOf(pasture(*ALL_HALVES.toTypedArray())),
    )

    /** Пекарня на одной стороне. */
    val BAKERY_ONE = tile(
        code = "bakery-one",
        title = "Пекарня",
        bakeries = listOf(CroissantBakerySpec(listOf(CroissantDirection.N))),
        pastures = listOf(pasture(E0, E1, S0, S1, W0, W1, bakeries = listOf(0))),
    )

    /** Пекарня на одной стороне с вывеской. */
    val BAKERY_ONE_SIGN = tile(
        code = "bakery-one-sign",
        title = "Пекарня с вывеской",
        bakeries = listOf(CroissantBakerySpec(listOf(CroissantDirection.N), sign = true)),
        pastures = listOf(pasture(E0, E1, S0, S1, W0, W1, bakeries = listOf(0))),
    )

    /** Угловая пекарня на двух соседних сторонах. */
    val BAKERY_CORNER = tile(
        code = "bakery-corner",
        title = "Угловая пекарня",
        bakeries = listOf(
            CroissantBakerySpec(listOf(CroissantDirection.N, CroissantDirection.E), sign = true),
        ),
        pastures = listOf(pasture(S0, S1, W0, W1, bakeries = listOf(0))),
    )

    /** Прямая тропа, луга по обе стороны разделены. */
    val TRAIL_STRAIGHT = tile(
        code = "trail-straight",
        title = "Прямая тропа",
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.N, CroissantDirection.S))),
        pastures = listOf(pasture(N0, W0, W1, S0), pasture(N1, E0, E1, S1)),
    )

    /** Поворот тропы, внутренний и внешний луг разделены. */
    val TRAIL_CURVE = tile(
        code = "trail-curve",
        title = "Изгиб тропы",
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.N, CroissantDirection.W))),
        pastures = listOf(pasture(N0, W0), pasture(N1, E0, E1, S0, S1, W1)),
    )

    /** Сад, к которому сходятся три тропы: каждая заканчивается у сада, лугов три. */
    val TRAIL_CROSS = tile(
        code = "trail-cross",
        title = "Развилка троп",
        garden = true,
        trails = listOf(
            CroissantTrailSpec(listOf(CroissantDirection.N), garden = true),
            CroissantTrailSpec(listOf(CroissantDirection.E), garden = true),
            CroissantTrailSpec(listOf(CroissantDirection.S), garden = true),
        ),
        pastures = listOf(pasture(N1, E0), pasture(E1, S1), pasture(S0, W0, W1, N0)),
    )

    /** Сад, к которому сходятся четыре тропы: каждая заканчивается у сада, лугов четыре. */
    val TRAIL_JUNCTION = tile(
        code = "trail-junction",
        title = "Перекрёсток троп",
        garden = true,
        trails = CroissantDirection.entries.map { CroissantTrailSpec(listOf(it), garden = true) },
        pastures = listOf(pasture(N0, W0), pasture(N1, E0), pasture(E1, S1), pasture(S0, W1)),
    )

    /** Пекарня и тропа напротив неё, тропа входит в пекарню; луг разделён тропой на две части. */
    val BAKERY_TRAIL = tile(
        code = "bakery-trail",
        title = "Ворота пекарни",
        bakeries = listOf(CroissantBakerySpec(listOf(CroissantDirection.N))),
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.S), bakery = 0)),
        pastures = listOf(
            pasture(W0, W1, S0, bakeries = listOf(0)),
            pasture(E0, E1, S1, bakeries = listOf(0)),
        ),
    )

    /** Пекарня и тропа-дуга с востока на юг мимо неё: внутренний угол луга отделён, к пекарне примыкает внешний луг. */
    val BAKERY_TRAIL_T = tile(
        code = "bakery-trail-t",
        title = "Пекарня на распутье",
        bakeries = listOf(CroissantBakerySpec(listOf(CroissantDirection.N))),
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.E, CroissantDirection.S))),
        pastures = listOf(
            pasture(E0, S0, W0, W1, bakeries = listOf(0)),
            pasture(E1, S1),
        ),
    )

    /** Две пекарни напротив друг друга, тропа между ними. */
    val BAKERY_TWO = tile(
        code = "bakery-two",
        title = "Две пекарни",
        bakeries = listOf(
            CroissantBakerySpec(listOf(CroissantDirection.N)),
            CroissantBakerySpec(listOf(CroissantDirection.S)),
        ),
        trails = listOf(CroissantTrailSpec(listOf(CroissantDirection.E, CroissantDirection.W))),
        pastures = listOf(
            pasture(E0, W0, bakeries = listOf(0)),
            pasture(E1, W1, bakeries = listOf(1)),
        ),
    )

    /** Пекарня на трёх сторонах. */
    val BAKERY_THREE = tile(
        code = "bakery-three",
        title = "Большая пекарня",
        bakeries = listOf(
            CroissantBakerySpec(
                listOf(CroissantDirection.N, CroissantDirection.E, CroissantDirection.S),
                sign = true,
            ),
        ),
        pastures = listOf(pasture(W0, W1, bakeries = listOf(0))),
    )

    /** Пекарня по всему периметру. */
    val BAKERY_FOUR = tile(
        code = "bakery-four",
        title = "Пекарня-крепость",
        bakeries = listOf(
            CroissantBakerySpec(
                listOf(
                    CroissantDirection.N,
                    CroissantDirection.E,
                    CroissantDirection.S,
                    CroissantDirection.W,
                ),
                sign = true,
            ),
        ),
    )

    private fun tile(
        code: String,
        title: String,
        bakeries: List<CroissantBakerySpec> = emptyList(),
        trails: List<CroissantTrailSpec> = emptyList(),
        dairy: Boolean = false,
        garden: Boolean = false,
        pastures: List<CroissantPastureSpec> = emptyList(),
    ): CroissantTileType {
        val edges = CroissantDirection.entries.map { dir ->
            when {
                bakeries.any { dir in it.sides } -> CroissantEdge.BAKERY
                trails.any { dir in it.sides } -> CroissantEdge.TRAIL
                else -> CroissantEdge.PASTURE
            }
        }
        return CroissantTileType(code, title, edges, bakeries, trails, dairy, garden, pastures)
    }

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
