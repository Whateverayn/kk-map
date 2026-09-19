package io.github.whateverayn.kkmap.core.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

/**
 * N02 (国土数値情報 鉄道データ) の路線と駅を描くレイヤー群.
 * データは tools/rail-data/build_rail.py で生成して assets/rail/ に同梱している.
 *
 * - 路線: z11 未満だけ描く. 地理院タイルは低ズームで路線を間引いているため (z11 以上は地理院の線を使う).
 *   見た目は地理院の低ズーム用鉄道レイヤーに合わせる
 * - 駅: circle レイヤーなので, 低ズームでも間引かれずに常に点が出る.
 *   z9 未満は黒点, z9 以上は白丸
 * - 駅名: symbol レイヤーなので, 重なるときは省略される (乗り入れ路線の多い駅を優先)
 */
internal object RailLayers {
    private const val SOURCE_LINES = "kk-rail-lines"
    private const val SOURCE_STATIONS = "kk-rail-stations"
    private const val LAYER_LINE_LOW_ZOOM = "kk-rail-line-low-zoom"
    /** 駅の点. 経路のハイライトはこの下に差し込む (経路上でも駅の点が見えるように) */
    const val LAYER_STATION = "kk-rail-station"
    private const val LAYER_STATION_LABEL = "kk-rail-station-label"

    /** 地理院スタイルの低ズーム用鉄道レイヤー (build_style.py で非表示にしてある). 自前の路線はこの位置に差し込む */
    private const val GSI_LOW_ZOOM_RAIL_LAYER = "鉄道中心線ZL4-10"

    /** 地理院の線路が全路線そろうズーム */
    private const val GSI_FULL_RAIL_ZOOM = 11f

    /** 白丸に切り替えるズーム */
    private const val STATION_RING_ZOOM = 9f

    // 事業者種別 (N02_002)
    private const val TYPE_JR = 2

    /** 地理院スタイルが使っているフォント (glyphs はスタイルの URL から取得される) */
    private const val FONT = "NotoSansJP-Regular"

    fun install(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_LINES, URI("asset://rail/lines.geojson")))
        style.addSource(GeoJsonSource(SOURCE_STATIONS, URI("asset://rail/stations.geojson")))

        val lowZoomLine = LineLayer(LAYER_LINE_LOW_ZOOM, SOURCE_LINES).withProperties(
            // 地理院の低ズーム用鉄道レイヤーと同じ色
            lineColor(rgb(100, 100, 100)),
            lineWidth(
                interpolate(
                    linear(), zoom(),
                    stop(4, 1.0),
                    stop(8, 1.5),
                    stop(10, match(get("type"), literal(1.5), stop(TYPE_JR, 2.5))),
                )
            ),
        ).also { it.maxZoom = GSI_FULL_RAIL_ZOOM }
        if (style.getLayer(GSI_LOW_ZOOM_RAIL_LAYER) != null) {
            style.addLayerAbove(lowZoomLine, GSI_LOW_ZOOM_RAIL_LAYER)
        } else {
            style.addLayer(lowZoomLine)
        }

        style.addLayer(
            CircleLayer(LAYER_STATION, SOURCE_STATIONS).withProperties(
                circleColor(step(zoom(), rgb(0x22, 0x22, 0x22), stop(STATION_RING_ZOOM, rgb(0xFF, 0xFF, 0xFF)))),
                circleRadius(
                    interpolate(
                        linear(), zoom(),
                        stop(5, 0.7),
                        stop(8, 1.2),
                        stop(STATION_RING_ZOOM, 2.5),
                        stop(13, 4.5),
                        stop(17, 7.0),
                    )
                ),
                circleStrokeColor(Color.rgb(0x22, 0x22, 0x22)),
                circleStrokeWidth(step(zoom(), literal(0f), stop(STATION_RING_ZOOM, 1.2f), stop(13, 1.5f))),
            )
        )
        style.addLayer(
            SymbolLayer(LAYER_STATION_LABEL, SOURCE_STATIONS).withProperties(
                textField(get("name")),
                textFont(arrayOf(FONT)),
                textSize(
                    interpolate(
                        linear(), zoom(),
                        stop(9, 10),
                        stop(14, 13),
                    )
                ),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.6f)),
                textColor(Color.rgb(0x22, 0x22, 0x22)),
                textHaloColor(Color.WHITE),
                textHaloWidth(1.5f),
                // 値が小さいものから配置される. 乗り入れ路線が多い駅を優先する
                symbolSortKey(product(get("lines"), literal(-1))),
            ).also { it.minZoom = 9f }
        )
    }
}
