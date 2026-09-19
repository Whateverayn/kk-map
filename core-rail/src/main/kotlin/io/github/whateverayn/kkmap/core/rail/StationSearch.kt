package io.github.whateverayn.kkmap.core.rail

import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 駅名の検索. 並び順は入力内容だけから一意に決まる (使用頻度や履歴では並べ替えない).
 *
 * 1. 完全一致 → 前方一致 → 部分一致
 * 2. 駅名の短い順
 * 3. 駅名の文字コード順
 * 4. N02 のグループコード順
 */
class StationSearch(private val graph: RailGraph) {
    private val normalizedNames: List<String> = graph.groups.map { normalize(it.name) }

    fun search(query: String): List<StationGroup> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return graph.groups.indices
            .mapNotNull { i ->
                val name = normalizedNames[i]
                val rank = when {
                    name == q -> 0
                    name.startsWith(q) -> 1
                    name.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                rank to graph.groups[i]
            }
            .sortedWith(
                compareBy<Pair<Int, StationGroup>> { it.first }
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.name }
                    .thenBy { it.second.code }
            )
            .map { it.second }
    }

    /** [center] から [radiusMeters] 以内の駅 (近い順). 乗換先の候補に使う */
    fun nearby(center: StationGroup, radiusMeters: Double = 300.0): List<StationGroup> =
        graph.groups
            .filter { it.index != center.index }
            .map { it to distanceMeters(center.position, it.position) }
            .filter { it.second <= radiusMeters }
            .sortedWith(compareBy<Pair<StationGroup, Double>> { it.second }.thenBy { it.first.code })
            .map { it.first }

    companion object {
        /** 全角英数を半角に, ヶ/ヵ を ケ/カ にそろえ, 空白を除く */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('ヶ', 'ケ')
                .replace('ヵ', 'カ')
                .filterNot { it.isWhitespace() }
    }
}

/** 2点間の距離 (m). ハバーサイン式 */
fun distanceMeters(a: LonLat, b: LonLat): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
