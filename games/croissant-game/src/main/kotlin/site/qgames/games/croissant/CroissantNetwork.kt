package site.qgames.games.croissant

import java.util.UUID

/** Вариант выкладки: клетка и повороты, при которых тайл сюда подходит. */
data class CroissantPlacement(val x: Int, val y: Int, val rotations: List<Int>)

/**
 * Поле партии, разложенное по клеткам: тайлы, их геометрия и проверка стыковки рёбер. Чистые вычисления без состояния.
 */
class CroissantLayout(board: List<CroissantPlacedTile>) {

    private val cells: Map<String, CroissantPlacedTile> = board.associateBy { it.id }
    private val shapes: Map<String, CroissantShape> = board.associate {
        it.id to CroissantShape(type(it.tile), it.rotation)
    }

    fun type(code: String): CroissantTileType = CroissantCatalog.type(code)

    fun typeOf(placed: CroissantPlacedTile): CroissantTileType = type(placed.tile)

    fun byId(tileId: String): CroissantPlacedTile? = cells[tileId]

    fun shape(placed: CroissantPlacedTile): CroissantShape = shapes.getValue(placed.id)

    fun cellId(x: Int, y: Int): String = "$x:$y"

    fun cell(x: Int, y: Int): CroissantPlacedTile? = cells[cellId(x, y)]

    fun neighbour(x: Int, y: Int, dir: CroissantDirection): CroissantPlacedTile? =
        cell(x + dir.dx, y + dir.dy)

    /** Подходит ли тайл [code] в клетку под поворотом [rotation]: рёбра стыкуются со всеми соседями. */
    fun accepts(code: String, x: Int, y: Int, rotation: Int): Boolean {
        if (!isValidRotation(rotation)) return false
        if (cell(x, y) != null) return false
        val shape = CroissantShape(type(code), rotation)
        return CroissantDirection.entries.all { dir ->
            val neighbour = neighbour(x, y, dir) ?: return@all true
            shape.edge(dir) == shape(neighbour).edge(dir.opposite)
        }
    }

    /** Все варианты выкладки тайла [code]: клетки у края поля с поворотами, при которых тайл подходит. */
    fun placements(code: String): List<CroissantPlacement> {
        if (cells.isEmpty()) return listOf(CroissantPlacement(0, 0, listOf(0)))
        val candidates = LinkedHashSet<Pair<Int, Int>>()
        cells.values.forEach { placed ->
            CroissantDirection.entries.forEach { dir ->
                val x = placed.x + dir.dx
                val y = placed.y + dir.dy
                if (cell(x, y) == null) candidates += Pair(x, y)
            }
        }
        return candidates.sortedWith(compareBy({ it.second }, { it.first })).mapNotNull { (x, y) ->
            val fitting = ROTATIONS.filter { accepts(code, x, y, it) }
            if (fitting.isEmpty()) null else CroissantPlacement(x, y, fitting)
        }
    }

    private fun isValidRotation(rotation: Int) = rotation % 90 == 0 && Math.floorMod(rotation, 360) in ROTATIONS

    private companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)
    }
}

/**
 * Связные участки сети: пересобираются из поля целиком после каждого тайла. Так нет книги слияний — участки
 * объединяются через общий корень автоматически, а кондитеры переезжают на объединённый участок.
 */
internal object CroissantNetwork {

    private data class Atom(val type: CroissantSpotType, val tileId: String, val index: Int)

    private fun atom(type: CroissantSpotType, tileId: String, index: Int) = Atom(type, tileId, index)

    /**
     * Участки поля [layout]. Идентификаторы сохраняются: у объединённого участка остаётся наименьший из прежних, у
     * нового он выводится из тайла. [previous] — участки до последнего тайла: по ним переносятся кондитеры и
     * подбираются идентификаторы.
     */
    fun rebuild(layout: CroissantLayout, board: List<CroissantPlacedTile>, previous: List<CroissantSpot>): List<CroissantSpot> {
        val atoms = ArrayList<Atom>()
        board.forEach { placed ->
            val type = layout.typeOf(placed)
            type.bakeries.indices.forEach { atoms += atom(CroissantSpotType.BAKERY, placed.id, it) }
            type.trails.indices.forEach { atoms += atom(CroissantSpotType.TRAIL, placed.id, it) }
            if (type.structures.dairy) atoms += atom(CroissantSpotType.DAIRY, placed.id, 0)
            type.pastures.indices.forEach { atoms += atom(CroissantSpotType.PASTURE, placed.id, it) }
        }
        val parent = atoms.associateWith { it }.toMutableMap()

        fun find(start: Atom): Atom {
            var current = start
            while (parent.getValue(current) != current) current = parent.getValue(current)
            return current
        }

        fun union(a: Atom, b: Atom) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        board.forEach { placed ->
            val shape = layout.shape(placed)
            val type = layout.typeOf(placed)
            type.bakeries.indices.forEach { index ->
                shape.bakerySides(index).forEach { dir ->
                    val neighbour = layout.neighbour(placed.x, placed.y, dir) ?: return@forEach
                    val other = layout.shape(neighbour).bakeryAt(dir.opposite) ?: return@forEach
                    union(
                        atom(CroissantSpotType.BAKERY, placed.id, index),
                        atom(CroissantSpotType.BAKERY, neighbour.id, other),
                    )
                }
            }
            type.trails.indices.forEach { index ->
                shape.trailSides(index).forEach { dir ->
                    val neighbour = layout.neighbour(placed.x, placed.y, dir) ?: return@forEach
                    val other = layout.shape(neighbour).trailAt(dir.opposite) ?: return@forEach
                    union(
                        atom(CroissantSpotType.TRAIL, placed.id, index),
                        atom(CroissantSpotType.TRAIL, neighbour.id, other),
                    )
                }
            }
            type.pastures.indices.forEach { index ->
                shape.pastureHalves(index).forEach { half ->
                    val neighbour = layout.neighbour(placed.x, placed.y, half.dir) ?: return@forEach
                    val facing = CroissantHalfEdge(half.dir.opposite, half.half)
                    val other = layout.shape(neighbour).pastureAt(facing) ?: return@forEach
                    union(
                        atom(CroissantSpotType.PASTURE, placed.id, index),
                        atom(CroissantSpotType.PASTURE, neighbour.id, other),
                    )
                }
            }
        }

        val groups: Map<Atom, List<Atom>> = atoms.groupBy(::find)

        val previousByMember: Map<Triple<String, CroissantSpotType, Int>, CroissantSpot> = buildMap {
            previous.forEach { spot ->
                spot.members.forEach { member -> put(Triple(member.tileId, spot.type, member.index), spot) }
            }
        }
        val idByRoot: Map<Atom, String> = groups.entries.associate { (root, members) ->
            val inherited = members.mapNotNull {
                previousByMember[Triple(it.tileId, it.type, it.index)]?.id
            }
            root to (inherited.minOrNull() ?: freshId(members.first()))
        }
        val idByAtom: Map<Atom, String> = groups.entries.flatMap { (root, members) ->
            members.map { it to idByRoot.getValue(root) }
        }.toMap()

        val spots = groups.entries.map { (root, members) ->
            val type = root.type
            val sorted = members.sortedWith(compareBy({ it.tileId }, { it.index }))
            val carried = sorted
                .mapNotNull { previousByMember[Triple(it.tileId, it.type, it.index)] }
                .flatMap { it.confectioners }
                .distinct()
            val open = openEnds(layout, sorted, type)
            val signs = if (type == CroissantSpotType.BAKERY) {
                sorted.sumOf { if (layout.typeOf(layout.byId(it.tileId)!!).bakeries[it.index].sign) 1 else 0 }
            } else {
                0
            }
            val bakeries = if (type == CroissantSpotType.PASTURE) {
                sorted.flatMap { member ->
                    layout.typeOf(layout.byId(member.tileId)!!).pastures[member.index].adjacentBakeries.mapNotNull { bakeryIndex ->
                        idByAtom[atom(CroissantSpotType.BAKERY, member.tileId, bakeryIndex)]
                    }
                }.distinct()
            } else {
                emptyList()
            }
            CroissantSpot(
                id = idByRoot.getValue(root),
                type = type,
                members = sorted.map { CroissantSpotMember(it.tileId, it.index) },
                open = open,
                signs = signs,
                confectioners = carried,
                complete = type != CroissantSpotType.PASTURE && open == 0,
                bakeries = bakeries,
            )
        }
        return spots.sortedBy { it.id }
    }

    /**
     * Сколько выходов участка смотрит в пустые клетки. Пекарни и тропы перечисляют стороны, сыроварня — все восемь
     * соседей. У лугов выходов нет: они не завершаются.
     */
    private fun openEnds(layout: CroissantLayout, members: List<Atom>, type: CroissantSpotType): Int = when (type) {
        CroissantSpotType.BAKERY, CroissantSpotType.TRAIL -> members.sumOf { member ->
            val placed = layout.byId(member.tileId)!!
            val shape = layout.shape(placed)
            val sides = if (type == CroissantSpotType.BAKERY) {
                shape.bakerySides(member.index)
            } else {
                shape.trailSides(member.index)
            }
            sides.count { dir -> layout.neighbour(placed.x, placed.y, dir) == null }
        }
        CroissantSpotType.DAIRY -> {
            val placed = layout.byId(members.first().tileId)!!
            DAIRY_SIDES.count { (dx, dy) -> layout.cell(placed.x + dx, placed.y + dy) == null }
        }
        CroissantSpotType.PASTURE -> 0
    }

    private fun freshId(atom: Atom): String {
        val prefix = when (atom.type) {
            CroissantSpotType.BAKERY -> "b"
            CroissantSpotType.TRAIL -> "t"
            CroissantSpotType.DAIRY -> "d"
            CroissantSpotType.PASTURE -> "p"
        }
        return "$prefix:${atom.tileId}:${atom.index}"
    }

    /** Восемь соседей клетки. */
    val DAIRY_SIDES = listOf(
        Pair(-1, -1), Pair(0, -1), Pair(1, -1),
        Pair(-1, 0), Pair(1, 0),
        Pair(-1, 1), Pair(0, 1), Pair(1, 1),
    )
}

/** Подсчёт «Золотых крошек» по участку и большинству кондитеров. */
internal object CroissantScoring {

    /** Крошки за завершённый участок; луг при завершении не считается. */
    fun completed(spot: CroissantSpot): Int = when (spot.type) {
        CroissantSpotType.BAKERY -> BAKERY_PER_TILE * spot.members.size + BAKERY_PER_SIGN * spot.signs
        CroissantSpotType.TRAIL -> TRAIL_PER_TILE * spot.members.size
        CroissantSpotType.DAIRY -> DAIRY_COMPLETE
        CroissantSpotType.PASTURE -> 0
    }

    /** Крошки за участок в итоговом подсчёте. [spots] — все участки поля: луга смотрят на завершённые пекарни. */
    fun final(spot: CroissantSpot, layout: CroissantLayout, spots: List<CroissantSpot>): Int = when (spot.type) {
        CroissantSpotType.BAKERY -> spot.members.size + spot.signs
        CroissantSpotType.TRAIL -> spot.members.size
        CroissantSpotType.DAIRY -> {
            val placed = layout.byId(spot.members.first().tileId)!!
            1 + CroissantNetwork.DAIRY_SIDES.count { (dx, dy) -> layout.cell(placed.x + dx, placed.y + dy) != null }
        }
        CroissantSpotType.PASTURE -> PASTURE_PER_BAKERY * spot.bakeries
            .distinct()
            .count { id -> spots.firstOrNull { it.id == id }?.complete == true }
    }

    /** Игроки с большинством кондитеров на участке: при равенстве крошки получают все лидеры. */
    fun winners(spot: CroissantSpot): List<UUID> {
        val counts = spot.confectioners.groupingBy { it.playerId }.eachCount()
        val best = counts.values.maxOrNull() ?: return emptyList()
        return counts.filterValues { it == best }.keys.sortedBy { it.toString() }
    }

    const val BAKERY_PER_TILE = 2
    const val BAKERY_PER_SIGN = 2
    const val TRAIL_PER_TILE = 1
    const val DAIRY_COMPLETE = 9
    const val PASTURE_PER_BAKERY = 3
}
