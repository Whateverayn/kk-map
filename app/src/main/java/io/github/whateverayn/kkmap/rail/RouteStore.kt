package io.github.whateverayn.kkmap.rail

import android.content.Context
import io.github.whateverayn.kkmap.core.rail.LonLat
import io.github.whateverayn.kkmap.core.rail.RouteCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 指定した区間を保存する (アプリが落ちたり再起動したりしても消えないように).
 *
 * 経路は計算結果 (形状・路線名・距離・通過駅) をそのまま保存し, 読み込み時に計算し直さない.
 * 駅はグループコードで保存するので, 線路データの更新でコードが消えた区間は読み飛ばす.
 */
class RouteStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "route.json")

    fun save(sections: List<RouteCandidate>) {
        val array = JSONArray()
        for (s in sections) {
            val coords = JSONArray()
            for (p in s.coordinates) {
                coords.put(Math.round(p.lon * COORD_SCALE))
                coords.put(Math.round(p.lat * COORD_SCALE))
            }
            array.put(
                JSONObject()
                    .put(KEY_STATIONS, JSONArray(s.stations.map { it.code }))
                    .put(KEY_LINES, JSONArray(s.lines))
                    .put(KEY_LENGTH, s.lengthMeters)
                    .put(KEY_COORDINATES, coords)
            )
        }
        // 書きかけで落ちても前の内容を壊さないよう, 一時ファイルに書いてから置き換える
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    fun load(data: RailData): List<RouteCandidate> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val stations = o.getJSONArray(KEY_STATIONS).let { a ->
                    List(a.length()) { data.groupByCode(a.getInt(it)) ?: return@runCatching null }
                }
                val lines = o.getJSONArray(KEY_LINES).let { a -> List(a.length()) { a.getString(it) } }
                val coords = o.getJSONArray(KEY_COORDINATES).let { a ->
                    List(a.length() / 2) { LonLat(a.getLong(it * 2) / COORD_SCALE, a.getLong(it * 2 + 1) / COORD_SCALE) }
                }
                RouteCandidate(
                    from = stations.first(),
                    to = stations.last(),
                    lengthMeters = o.getDouble(KEY_LENGTH),
                    lines = lines,
                    stations = stations,
                    coordinates = coords,
                )
            }.getOrNull()
        }
    }

    private companion object {
        const val COORD_SCALE = 1_000_000.0
        const val KEY_STATIONS = "stations"
        const val KEY_LINES = "lines"
        const val KEY_LENGTH = "length"
        const val KEY_COORDINATES = "coordinates"
    }
}
