package io.github.whateverayn.kkmap.core.map

import org.maplibre.android.geometry.LatLng
import kotlin.math.cos

/**
 * 経路 (折れ線) 上のどこまで進んだかを追う.
 *
 * - 現在地を折れ線上の最寄りの点に当てはめる
 * - 進み具合は後戻りさせない. 環状線のように同じ場所の近くを2度通る経路で, 逆側に飛ばないようにするため
 * - 経路から [maxOffRouteMeters] より離れているときは進み具合を更新しない (乗車前や, 別の線に乗っているとき)
 */
internal class RouteProgress(
    private val points: List<LatLng>,
    private val maxOffRouteMeters: Double = 1_000.0,
) {
    /** 通過済みの位置: 線分 [segment] (points[segment] → points[segment + 1]) 上の割合 [fraction] */
    private var segment = 0
    private var fraction = 0.0

    fun update(location: LatLng) {
        if (points.size < 2) return
        var bestSegment = -1
        var bestFraction = 0.0
        var bestDistance = Double.MAX_VALUE
        for (i in segment until points.size - 1) {
            val (t, d) = project(location, points[i], points[i + 1])
            // 今いる線分の中では手前に戻らない
            val clampedT = if (i == segment) maxOf(t, fraction) else t
            val dist = if (clampedT == t) d else distance(location, interpolate(points[i], points[i + 1], clampedT))
            if (dist < bestDistance) {
                bestDistance = dist
                bestSegment = i
                bestFraction = clampedT
            }
        }
        if (bestSegment >= 0 && bestDistance <= maxOffRouteMeters) {
            segment = bestSegment
            fraction = bestFraction
        }
    }

    /** 通過済みの部分 (始点から現在の位置まで) */
    fun passed(): List<LatLng> =
        if (points.size < 2) emptyList() else points.subList(0, segment + 1) + current()

    /** 未通過の部分 (現在の位置から終点まで) */
    fun remaining(): List<LatLng> =
        if (points.size < 2) points else listOf(current()) + points.subList(segment + 1, points.size)

    private fun current(): LatLng = interpolate(points[segment], points[segment + 1], fraction)

    private companion object {
        const val EARTH_RADIUS = 6_371_000.0

        /** 点 p を線分 a-b に射影したときの割合 (0..1) と距離 (m). 短い線分なので平面近似でよい */
        fun project(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
            val k = cos(Math.toRadians(a.latitude))
            val ax = a.longitude * k
            val bx = b.longitude * k
            val px = p.longitude * k
            val dx = bx - ax
            val dy = b.latitude - a.latitude
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (p.latitude - a.latitude) * dy) / len2).coerceIn(0.0, 1.0)
            return t to distance(p, interpolate(a, b, t))
        }

        fun interpolate(a: LatLng, b: LatLng, t: Double) =
            LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

        fun distance(a: LatLng, b: LatLng): Double {
            val k = cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = Math.toRadians(b.longitude - a.longitude) * k
            val dy = Math.toRadians(b.latitude - a.latitude)
            return EARTH_RADIUS * kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }
}
