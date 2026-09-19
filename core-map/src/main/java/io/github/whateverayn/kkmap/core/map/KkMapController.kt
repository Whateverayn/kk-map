package io.github.whateverayn.kkmap.core.map

import android.graphics.Color
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.min

/**
 * 地図の操作をまとめたクラス. UI フレームワーク (Compose / Car App) には依存しない.
 *
 * - 現在地と目的地を描画する
 * - followMode 中は, 現在地と目的地の両方が収まるようにカメラを合わせる (fitBounds 相当)
 * - ユーザがジェスチャで地図を動かしたら followMode を解除する
 */
class KkMapController private constructor(
    val map: MapLibreMap,
    private val style: Style,
) {
    private var user: LatLng? = null
    private var destination: LatLng? = null

    private val userSource = GeoJsonSource(SOURCE_USER)
    private val destinationSource = GeoJsonSource(SOURCE_DESTINATION)

    /** followMode が変わったときに呼ばれる (追従再開ボタンの表示切り替え用) */
    var onFollowModeChanged: ((Boolean) -> Unit)? = null

    /** カメラを合わせるときの余白 (px). left, top, right, bottom */
    private var padding: IntArray = IntArray(4) { DEFAULT_PADDING_PX }

    // MapLibre 既定の UI の余白 (px). setOverlayInsets() でこれに上乗せする
    private val baseCompassMargins = map.uiSettings.run { intArrayOf(compassMarginLeft, compassMarginTop, compassMarginRight, compassMarginBottom) }
    private val baseLogoMargins = map.uiSettings.run { intArrayOf(logoMarginLeft, logoMarginTop, logoMarginRight, logoMarginBottom) }
    private val baseAttributionMargins = map.uiSettings.run {
        intArrayOf(attributionMarginLeft, attributionMarginTop, attributionMarginRight, attributionMarginBottom)
    }

    /** 追従中にこれ以上ズームインしない (現在地と目的地が近すぎるとき用) */
    var maxFollowZoom: Double = DEFAULT_MAX_FOLLOW_ZOOM

    var followMode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            onFollowModeChanged?.invoke(value)
            if (value) updateCamera()
        }

    init {
        // 鉄道は現在地・目的地より下に描く
        RailLayers.install(style)
        style.addSource(userSource)
        style.addSource(destinationSource)
        style.addLayer(
            CircleLayer(LAYER_DESTINATION, SOURCE_DESTINATION).withProperties(
                circleRadius(8f),
                circleColor(Color.rgb(0xD3, 0x2F, 0x2F)),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            CircleLayer(LAYER_USER, SOURCE_USER).withProperties(
                circleRadius(7f),
                circleColor(Color.rgb(0x19, 0x76, 0xD2)),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(3f),
            )
        )
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                followMode = false
            }
        }
    }

    /**
     * 地図の上に重なっている UI の大きさ (px) を指定する.
     * コンパス・ロゴ・出典ボタンをその内側に移し, 追従時のカメラの余白にも加える.
     * 地図自体はステータスバーやナビゲーションバーの下まで描いたままにできる.
     */
    fun setOverlayInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val ui = map.uiSettings
        baseCompassMargins.let { ui.setCompassMargins(it[0] + left, it[1] + top, it[2] + right, it[3] + bottom) }
        baseLogoMargins.let { ui.setLogoMargins(it[0] + left, it[1] + top, it[2] + right, it[3] + bottom) }
        baseAttributionMargins.let { ui.setAttributionMargins(it[0] + left, it[1] + top, it[2] + right, it[3] + bottom) }
        padding = intArrayOf(
            DEFAULT_PADDING_PX + left,
            DEFAULT_PADDING_PX + top,
            DEFAULT_PADDING_PX + right,
            DEFAULT_PADDING_PX + bottom,
        )
        if (followMode) updateCamera()
    }

    fun setUserLocation(latLng: LatLng?) {
        user = latLng
        userSource.setGeoJson(latLng.toFeatureCollection())
        if (followMode) updateCamera()
    }

    fun setDestination(latLng: LatLng?) {
        destination = latLng
        destinationSource.setGeoJson(latLng.toFeatureCollection())
        if (followMode) updateCamera()
    }

    private fun updateCamera() {
        val u = user
        val d = destination
        val position: CameraPosition = when {
            u != null && d != null && u.distanceTo(d) > SAME_POINT_METERS -> {
                val bounds = LatLngBounds.Builder().include(u).include(d).build()
                map.getCameraForLatLngBounds(bounds, padding) ?: return
            }
            u != null -> singlePointCamera(u)
            d != null -> singlePointCamera(d)
            else -> return
        }
        val clamped = CameraPosition.Builder(position)
            .zoom(min(position.zoom, maxFollowZoom))
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(clamped), CAMERA_DURATION_MS)
    }

    private fun singlePointCamera(target: LatLng): CameraPosition {
        val zoom = map.cameraPosition.zoom.takeIf { it >= MIN_SINGLE_POINT_ZOOM } ?: DEFAULT_SINGLE_POINT_ZOOM
        return CameraPosition.Builder().target(target).zoom(zoom).build()
    }

    private fun LatLng?.toFeatureCollection(): FeatureCollection =
        if (this == null) {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        } else {
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
        }

    companion object {
        private const val SOURCE_USER = "kk-user"
        private const val SOURCE_DESTINATION = "kk-destination"
        private const val LAYER_USER = "kk-user"
        private const val LAYER_DESTINATION = "kk-destination"

        private const val DEFAULT_PADDING_PX = 120
        private const val DEFAULT_MAX_FOLLOW_ZOOM = 16.0
        private const val DEFAULT_SINGLE_POINT_ZOOM = 15.0
        private const val MIN_SINGLE_POINT_ZOOM = 10.0
        private const val SAME_POINT_METERS = 5.0
        private const val CAMERA_DURATION_MS = 500

        /** 日本全体が見える初期カメラ */
        val INITIAL_CAMERA: CameraPosition = CameraPosition.Builder()
            .target(LatLng(36.0, 137.0))
            .zoom(4.5)
            .build()

        /**
         * [mapView] に地理院スタイルを読み込み, 準備ができたら [onReady] を呼ぶ.
         * [KkMap.init] と mapView.onCreate() は呼び出し側で先に済ませておくこと.
         */
        fun attach(mapView: MapView, onReady: (KkMapController) -> Unit) {
            mapView.getMapAsync { map ->
                map.cameraPosition = INITIAL_CAMERA
                map.setStyle(Style.Builder().fromUri(KkMap.GSI_STD_STYLE_URI)) { style ->
                    onReady(KkMapController(map, style))
                }
            }
        }
    }
}
