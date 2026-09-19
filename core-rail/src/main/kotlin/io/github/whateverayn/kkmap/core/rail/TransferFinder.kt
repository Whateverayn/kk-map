package io.github.whateverayn.kkmap.core.rail

import java.util.PriorityQueue

/** 乗換の候補: [via] まで線路で行き, 歩いて [next] に移る */
data class TransferSuggestion(
    val via: StationGroup,
    val next: StationGroup,
    val walkMeters: Double,
    /** 乗車駅 → via と next → 降車駅の線路の距離に, 徒歩の距離を足したもの. 並び順に使う */
    val totalMeters: Double,
)

/**
 * 乗車駅と降車駅が線路でつながっていないときに, どこで乗り換えればよいかを提案する.
 *
 * 乗車駅から線路でたどれる駅 A と, 降車駅へ線路でたどれる駅 B のうち,
 * 歩いて移れる距離 ([maxWalkMeters] 以内) にある組を探し, 合計距離の短い順に返す.
 * 同じ駅 (グループ) の中での乗換は徒歩 0m として扱う.
 */
class TransferFinder(private val graph: RailGraph) {

    fun suggest(from: StationGroup, to: StationGroup, maxWalkMeters: Double = 500.0, limit: Int = 8): List<TransferSuggestion> {
        val fromDist = groupDistances(from)
        val toDist = groupDistances(to)

        // 降車駅側の駅を, 約1km四方の格子に振り分けておく (近い駅だけ比べるため)
        val cellSize = 0.01
        fun cell(p: LonLat) = Pair(Math.floorDiv((p.lon / cellSize).toInt(), 1), Math.floorDiv((p.lat / cellSize).toInt(), 1))
        val grid = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for (g in toDist.indices) if (toDist[g] < Double.MAX_VALUE) {
            grid.getOrPut(cell(graph.groups[g].position)) { mutableListOf() }.add(g)
        }

        val best = HashMap<Int, TransferSuggestion>() // via ごとに最良の1つ
        for (a in fromDist.indices) {
            if (fromDist[a] == Double.MAX_VALUE) continue
            val pa = graph.groups[a].position
            val (cx, cy) = cell(pa)
            for (dx in -1..1) for (dy in -1..1) {
                for (b in grid[Pair(cx + dx, cy + dy)].orEmpty()) {
                    val walk = if (a == b) 0.0 else distanceMeters(pa, graph.groups[b].position)
                    if (walk > maxWalkMeters) continue
                    val total = fromDist[a] + walk + toDist[b]
                    val current = best[a]
                    if (current == null || total < current.totalMeters) {
                        best[a] = TransferSuggestion(graph.groups[a], graph.groups[b], walk, total)
                    }
                }
            }
        }
        return best.values
            .filter { it.via.index != from.index && it.next.index != to.index }
            .sortedWith(compareBy<TransferSuggestion> { it.totalMeters }.thenBy { it.via.code })
            .take(limit)
    }

    /** 駅 [start] から線路でたどったときの, 各駅 (グループ) までの最短距離. たどれなければ Double.MAX_VALUE */
    private fun groupDistances(start: StationGroup): DoubleArray {
        val dist = DoubleArray(graph.nodeCount) { Double.MAX_VALUE }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (s in graph.groupNodes[start.index]) {
            dist[s] = 0.0
            queue.add(0.0 to s)
        }
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()
            if (d > dist[n]) continue
            for (i in graph.adjacencyStart[n] until graph.adjacencyStart[n + 1]) {
                val e = graph.adjacency[i]
                val m = graph.otherEnd(e, n)
                val nd = d + graph.edgeLength[e]
                if (nd < dist[m]) {
                    dist[m] = nd
                    queue.add(nd to m)
                }
            }
        }
        val byGroup = DoubleArray(graph.groups.size) { Double.MAX_VALUE }
        for (n in 0 until graph.nodeCount) {
            val g = graph.nodeGroup[n]
            if (g >= 0 && dist[n] < byGroup[g]) byGroup[g] = dist[n]
        }
        return byGroup
    }
}
