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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * 地図の操作をまとめたクラス. UI フレームワーク (Compose / Car App) には依存しない.
 *
 * - 現在地と目的地, 経路のハイライト (未通過と通過済みで色を分ける) を描画する
 * - 追従 ([FollowMode]) の間は, 現在地の更新に合わせてカメラを動かす
 * - ユーザがジェスチャで地図を動かしたら追従を解除する
 */
class KkMapController private constructor(
    val map: MapLibreMap,
    private val style: Style,
) {
    private var user: LatLng? = null
    private var destination: LatLng? = null

    private val userSource = GeoJsonSource(SOURCE_USER)
    private val destinationSource = GeoJsonSource(SOURCE_DESTINATION)
    private val routeRemainingSource = GeoJsonSource(SOURCE_ROUTE_REMAINING)
    private val routePassedSource = GeoJsonSource(SOURCE_ROUTE_PASSED)
    private var routeProgress: RouteProgress? = null

    /** followMode が変わったときに呼ばれる (追従ボタンの表示切り替え用) */
    var onFollowModeChanged: ((FollowMode) -> Unit)? = null

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

    var followMode: FollowMode = FollowMode.FIT
        set(value) {
            if (field == value) return
            val previous = field
            when {
                // 全体表示に入る (現在地中心からでも, 追従していない状態からでも): いまの倍率を覚えておく
                value == FollowMode.FIT -> zoomBeforeFit = map.cameraPosition.zoom
                // 全体表示 → 現在地中心: 覚えておいた倍率に戻す.
                // 全体表示の途中で地図に触ると追従が外れる (倍率はその時のまま) ので, この経路は通らない
                previous == FollowMode.FIT && value == FollowMode.CENTER -> restoreZoom = zoomBeforeFit
            }
            field = value
            if (value != FollowMode.NONE) lastActiveFollowMode = value
            onFollowModeChanged?.invoke(value)
            if (value != FollowMode.NONE) updateCamera()
        }

    /** 最後に使った追従方式. 追従していないときに追従ボタンを押すと, これで再開する */
    private var lastActiveFollowMode: FollowMode = FollowMode.FIT

    /** 全体表示に入る直前の倍率 */
    private var zoomBeforeFit: Double? = null

    /** 次に現在地中心へカメラを動かすときに使う倍率 (1回使ったら消す) */
    private var restoreZoom: Double? = null

    /** 追従ボタンの動作: 追従していなければ前回の方式で再開し, 追従中なら方式を切り替える */
    fun resumeOrToggleFollow() {
        followMode = when (followMode) {
            FollowMode.NONE -> lastActiveFollowMode
            FollowMode.FIT -> FollowMode.CENTER
            FollowMode.CENTER -> FollowMode.FIT
        }
    }

    /**
     * キリのよいズームレベル (整数) に合わせて拡大・縮小する. 拡大は次の整数 (12.3 → 13), 縮小は前の整数 (12.3 → 12).
     * ズームレベルは1つで縮尺がちょうど2倍になり, 地理院地図の表示内容も整数ごとに切り替わる.
     * 全体表示の追従中は, 次の位置の更新で元に戻ってしまうので追従を解除する (現在地中心の追従は続ける)
     */
    fun zoomIn() = zoomTo(floor(map.cameraPosition.zoom + ZOOM_EPSILON) + 1)

    fun zoomOut() = zoomTo(ceil(map.cameraPosition.zoom - ZOOM_EPSILON) - 1)

    private fun zoomTo(zoom: Double) {
        if (followMode == FollowMode.FIT) followMode = FollowMode.NONE
        val clamped = zoom.coerceIn(map.minZoomLevel, map.maxZoomLevel)
        map.easeCamera(CameraUpdateFactory.zoomTo(clamped), CAMERA_DURATION_MS)
    }

    init {
        // 鉄道は現在地・目的地より下に描く
        RailLayers.install(style)
        installRouteLayers()
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
                followMode = FollowMode.NONE
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
        if (followMode != FollowMode.NONE) updateCamera()
    }

    fun setUserLocation(latLng: LatLng?) {
        user = latLng
        userSource.setGeoJson(latLng.toFeatureCollection())
        if (latLng != null) routeProgress?.update(latLng)
        updateRouteSources()
        if (followMode != FollowMode.NONE) updateCamera()
    }

    /**
     * ハイライトする経路. 区間ごとの折れ線を, 乗る順に渡す (null か空なら消す).
     * 設定し直すと進み具合は始点からやり直す.
     */
    fun setRoute(sections: List<List<LatLng>>?) {
        val points = ArrayList<LatLng>()
        for (section in sections.orEmpty()) {
            points.addAll(if (points.isNotEmpty() && points.last() == section.firstOrNull()) section.drop(1) else section)
        }
        routeProgress = if (points.size >= 2) RouteProgress(points).also { p -> user?.let { p.update(it) } } else null
        updateRouteSources()
        if (followMode != FollowMode.NONE) updateCamera()
    }

    private fun updateRouteSources() {
        val progress = routeProgress
        routeRemainingSource.setGeoJson(progress?.remaining().toLineFeatures())
        routePassedSource.setGeoJson(progress?.passed().toLineFeatures())
    }

    private fun List<LatLng>?.toLineFeatures(): FeatureCollection =
        if (this == null || size < 2) {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(map { Point.fromLngLat(it.longitude, it.latitude) }))
            )
        }

    private fun installRouteLayers() {
        style.addSource(routePassedSource)
        style.addSource(routeRemainingSource)
        val below = RailLayers.LAYER_STATION
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE_PASSED, SOURCE_ROUTE_PASSED).withProperties(
                lineColor(Color.rgb(0x9E, 0x9E, 0x9E)),
                lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
            below,
        )
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE_CASING, SOURCE_ROUTE_REMAINING).withProperties(
                lineColor(Color.WHITE),
                lineWidth(9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
            below,
        )
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE_REMAINING, SOURCE_ROUTE_REMAINING).withProperties(
                lineColor(Color.rgb(0xE6, 0x51, 0x00)),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
            below,
        )
    }

    fun setDestination(latLng: LatLng?) {
        destination = latLng
        destinationSource.setGeoJson(latLng.toFeatureCollection())
        if (followMode != FollowMode.NONE) updateCamera()
    }

    private fun updateCamera() {
        if (followMode == FollowMode.CENTER) {
            // 現在地を中央に保つ. ズームは利用者が決めたまま (現在地がまだ無ければ全体表示と同じ)
            user?.let {
                val zoom = restoreZoom
                restoreZoom = null
                val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(it, zoom) else CameraUpdateFactory.newLatLng(it)
                map.easeCamera(update, CAMERA_DURATION_MS)
                return
            }
        }
        val u = user
        val d = destination
        // 収めたい点: 現在地と, 未通過の経路 (経路が無ければ目的地)
        val targets = buildList {
            u?.let(::add)
            val remaining = routeProgress?.remaining()
            if (remaining != null) addAll(remaining) else d?.let(::add)
        }
        val position: CameraPosition = when {
            targets.size >= 2 && spansMoreThan(targets, SAME_POINT_METERS) -> {
                val bounds = LatLngBounds.Builder().includes(targets).build()
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

    private fun spansMoreThan(points: List<LatLng>, meters: Double): Boolean =
        points.any { it.distanceTo(points[0]) > meters }

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
        private const val SOURCE_ROUTE_REMAINING = "kk-route-remaining"
        private const val SOURCE_ROUTE_PASSED = "kk-route-passed"
        private const val LAYER_ROUTE_REMAINING = "kk-route-remaining"
        private const val LAYER_ROUTE_CASING = "kk-route-casing"
        private const val LAYER_ROUTE_PASSED = "kk-route-passed"

        private const val DEFAULT_PADDING_PX = 120
        private const val DEFAULT_MAX_FOLLOW_ZOOM = 16.0
        private const val DEFAULT_SINGLE_POINT_ZOOM = 15.0
        private const val MIN_SINGLE_POINT_ZOOM = 10.0
        private const val SAME_POINT_METERS = 5.0
        private const val CAMERA_DURATION_MS = 500

        /** ズームがほぼ整数 (12.0001 など) のとき, 同じ整数に留まらないようにする誤差 */
        private const val ZOOM_EPSILON = 0.01

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

/** 追従の方式 */
enum class FollowMode {
    /** 追従しない */
    NONE,

    /** 現在地と未通過の経路 (経路が無ければ目的地) が収まるように表示する */
    FIT,

    /** 現在地を画面の中央に保つ (ナビ型). ズームは変えない */
    CENTER,
}
