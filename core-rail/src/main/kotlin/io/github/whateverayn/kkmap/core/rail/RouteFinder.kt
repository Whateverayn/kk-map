package io.github.whateverayn.kkmap.core.rail

import java.util.PriorityQueue
import kotlin.math.abs

/** 乗車駅から降車駅までの経路の候補 */
data class RouteCandidate(
    val from: StationGroup,
    val to: StationGroup,
    val lengthMeters: Double,
    /** 通る路線名 (通った順). 表示用に雑音を除いたもの */
    val lines: List<String>,
    /** 通過する駅 (乗車駅と降車駅を含む) */
    val stations: List<StationGroup>,
    val coordinates: List<LonLat>,
) {
    /** 路線を乗り換える回数 (ラベル上の). 候補の並び順に使う */
    val lineChanges: Int get() = (lines.size - 1).coerceAtLeast(0)
}

/**
 * 乗車駅と降車駅の間の経路の候補を求める. 乗換検索ではなく, 線路が物理的につながっている経路だけを探す.
 *
 * - Yen の k-最短経路で候補を出す. 通過する駅の並びが同じもの (ホームや並走線の違いだけのもの) は
 *   1つにまとめ, 同じ駅を2度通るものは除く
 * - 並びは "路線の乗り換えが少ない順 → 短い順"
 * - 極端な遠回りを除くため, 全体の最短の [maxLengthRatio] 倍より長いものは出さない
 *   (環状線の逆回りは残る程度の値にしている)
 */
class RouteFinder(private val graph: RailGraph) {
    private companion object {
        /** Yen で調べる経路の上限 (駅の並びが同じものも含む). 計算時間の歯止め */
        const val MAX_RAW_PATHS = 80

        /** 駅の並びが同じ経路を同一とみなす距離の差 (割合) */
        const val SAME_ROUTE_LENGTH_RATIO = 0.05
    }

    fun find(
        from: StationGroup,
        to: StationGroup,
        maxCandidates: Int = 8,
        maxLengthRatio: Double = 3.0,
    ): List<RouteCandidate> {
        if (from.index == to.index) return emptyList()
        val sources = graph.groupNodes[from.index]
        val targets = graph.groupNodes[to.index]
        if (sources.isEmpty() || targets.isEmpty()) return emptyList()

        return yen(sources, targets.toSet(), maxCandidates, maxLengthRatio)
            .map { (path, stations) -> toCandidate(from, to, path, stations) }
            .sortedWith(compareBy<RouteCandidate> { it.lineChanges }.thenBy { it.lengthMeters })
    }

    private class Path(val nodes: IntArray, val edges: IntArray, val length: Double)

    // ---- Yen の k-最短経路 ----

    /**
     * 駅の並びが異なる経路を短い順に最大 [k] 本返す.
     * 駅の並びが同じ経路も Yen の手順上は "採用済み" として扱う (次の分岐を探す起点になるため) が, 本数には数えない.
     */
    private fun yen(sources: IntArray, targets: Set<Int>, k: Int, ratio: Double): List<Pair<Path, List<Int>>> {
        val first = dijkstra(sources, targets, BooleanArray(graph.nodeCount), emptySet(), Double.MAX_VALUE)
            ?: return emptyList()
        val limit = first.length * ratio
        val accepted = mutableListOf(first)
        val candidates = PriorityQueue<Path>(compareBy { it.length })
        val known = HashSet<List<Int>>().apply { add(first.edges.toList()) }
        val results = mutableListOf<Pair<Path, List<Int>>>()
        fun collect(path: Path) {
            val stations = stationSequence(path)
            if (stations.size != stations.toSet().size) return
            // 駅の並びが同じで距離もほぼ同じなら, 並走線やホームの違いだけとみなして捨てる.
            // 距離が違えば別経路 (大阪 → 新大阪 の本線と, うめきたからの支線は途中駅が無く駅の並びが同じ)
            val duplicate = results.any { (p, s) ->
                s == stations && abs(p.length - path.length) <= p.length * SAME_ROUTE_LENGTH_RATIO
            }
            if (!duplicate) results.add(path to stations)
        }
        collect(first)

        while (results.size < k && accepted.size < MAX_RAW_PATHS) {
            val last = accepted.last()
            // 乗車駅のホーム (始点) の選び方も分岐として扱う: i = -1 は "どの始点から出るか" の分岐
            for (i in -1 until last.edges.size) {
                val rootNodes = if (i < 0) IntArray(0) else last.nodes.copyOfRange(0, i + 1)
                val rootEdges = if (i < 0) IntArray(0) else last.edges.copyOfRange(0, i)
                val rootLength = rootEdges.sumOf { graph.edgeLength[it].toDouble() }

                val bannedEdges = HashSet<Int>()
                val bannedStarts = HashSet<Int>()
                for (p in accepted) {
                    if (i < 0) {
                        bannedStarts.add(p.nodes[0])
                    } else if (p.nodes.size > i + 1 && p.nodes.copyOfRange(0, i + 1).contentEquals(rootNodes)) {
                        bannedEdges.add(p.edges[i])
                    }
                }
                val bannedNodes = BooleanArray(graph.nodeCount)
                for (n in rootNodes.dropLast(1)) bannedNodes[n] = true

                val spurSources = if (i < 0) sources.filterNot { it in bannedStarts }.toIntArray() else intArrayOf(rootNodes.last())
                if (spurSources.isEmpty()) continue
                val spur = dijkstra(spurSources, targets, bannedNodes, bannedEdges, limit - rootLength) ?: continue

                val nodes = if (i < 0) spur.nodes else rootNodes.copyOfRange(0, i) + spur.nodes
                val edges = rootEdges + spur.edges
                if (known.add(edges.toList())) {
                    candidates.add(Path(nodes, edges, rootLength + spur.length))
                }
            }
            val next = candidates.poll() ?: break
            accepted.add(next)
            collect(next)
        }
        return results
    }

    private fun dijkstra(
        sources: IntArray,
        targets: Set<Int>,
        bannedNodes: BooleanArray,
        bannedEdges: Set<Int>,
        limit: Double,
    ): Path? {
        val dist = DoubleArray(graph.nodeCount) { Double.MAX_VALUE }
        val prevEdge = IntArray(graph.nodeCount) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (s in sources) {
            if (bannedNodes[s]) continue
            dist[s] = 0.0
            queue.add(0.0 to s)
        }
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()
            if (d > dist[n] || d > limit) continue
            if (n in targets) return buildPath(n, prevEdge, d)
            for (i in graph.adjacencyStart[n] until graph.adjacencyStart[n + 1]) {
                val e = graph.adjacency[i]
                if (e in bannedEdges) continue
                val m = graph.otherEnd(e, n)
                if (bannedNodes[m]) continue
                val nd = d + graph.edgeLength[e]
                if (nd < dist[m]) {
                    dist[m] = nd
                    prevEdge[m] = e
                    queue.add(nd to m)
                }
            }
        }
        return null
    }

    private fun buildPath(target: Int, prevEdge: IntArray, length: Double): Path {
        val nodes = ArrayList<Int>()
        val edges = ArrayList<Int>()
        var n = target
        nodes.add(n)
        while (prevEdge[n] >= 0) {
            val e = prevEdge[n]
            edges.add(e)
            n = graph.otherEnd(e, n)
            nodes.add(n)
        }
        return Path(nodes.reversed().toIntArray(), edges.reversed().toIntArray(), length)
    }

    // ---- 候補の組み立て ----

    private fun stationSequence(path: Path): List<Int> {
        val result = ArrayList<Int>()
        for (n in path.nodes) {
            val g = graph.nodeGroup[n]
            if (g >= 0 && result.lastOrNull() != g) result.add(g)
        }
        return result
    }

    private fun toCandidate(from: StationGroup, to: StationGroup, path: Path, stations: List<Int>): RouteCandidate {
        val coordinates = ArrayList<LonLat>()
        for ((i, e) in path.edges.withIndex()) {
            val pts = graph.edgePoints(e, forward = graph.edgeFrom[e] == path.nodes[i])
            coordinates.addAll(if (coordinates.isEmpty()) pts else pts.drop(1))
        }
        return RouteCandidate(
            from = from,
            to = to,
            lengthMeters = path.length,
            lines = lineLabel(path, from, to),
            stations = stations.map { graph.groups[it] },
            coordinates = coordinates,
        )
    }

    /**
     * 経路の路線名. 線路区間の路線名は分岐駅の構内などで別の路線名が混ざるので,
     * 途中駅がどの路線の駅か (駅節点の路線名) から作る.
     * - 乗車駅と降車駅の節点は含めない (どの路線のホームに着いたかは経路と関係ないため)
     * - 乗車駅の直後・降車駅の直前の1駅だけの別路線は除く (並走区間で別路線のホームが線路上に載っていることがある)
     * - 前後を同じ路線に挟まれた1駅だけの別路線は, 周りの路線に吸収する (例: 関西線の途中の奈良が "桜井線" の駅として載っている)
     * - 途中駅がなければ, 線路区間の路線名のうち最も長いものを使う
     */
    private fun lineLabel(path: Path, from: StationGroup, to: StationGroup): List<String> {
        val runs = ArrayList<Pair<String, Int>>() // 路線名と連続する駅数
        for (n in path.nodes) {
            val g = graph.nodeGroup[n]
            if (g < 0 || g == from.index || g == to.index) continue
            val line = graph.nodeLineName(n) ?: continue
            if (runs.isNotEmpty() && runs.last().first == line) {
                runs[runs.size - 1] = line to runs.last().second + 1
            } else {
                runs.add(line to 1)
            }
        }
        // 乗車駅の直後・降車駅の直前の1駅だけの別路線は除く.
        // 並走区間で別路線のホームが線路上に載っていることがある (例: 関西線の名古屋の手前のあおなみ線ささしまライブ)
        if (runs.size > 1 && runs.first().second == 1) runs.removeAt(0)
        if (runs.size > 1 && runs.last().second == 1) runs.removeAt(runs.size - 1)
        var changed = true
        while (changed) {
            changed = false
            for (i in 1 until runs.size - 1) {
                if (runs[i].second == 1 && runs[i - 1].first == runs[i + 1].first) {
                    runs[i - 1] = runs[i - 1].first to runs[i - 1].second + 1 + runs[i + 1].second
                    runs.removeAt(i + 1)
                    runs.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        if (runs.isNotEmpty()) return runs.map { it.first }

        val byLine = HashMap<String, Float>()
        for (e in path.edges) for ((line, length) in graph.edgeLines(e)) byLine.merge(line, length, Float::plus)
        return listOfNotNull(byLine.maxByOrNull { it.value }?.key)
    }
}
