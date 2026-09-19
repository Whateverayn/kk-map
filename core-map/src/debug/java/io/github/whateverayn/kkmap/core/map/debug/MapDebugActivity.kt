package io.github.whateverayn.kkmap.core.map.debug

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.whateverayn.kkmap.core.map.KkMap
import io.github.whateverayn.kkmap.core.map.KkMapController
import io.github.whateverayn.kkmap.core.map.bindLifecycle
import org.maplibre.android.maps.MapView

/**
 * :core-map 単体の動作確認用 (Compose を使わない).
 * - タップ: その地点を現在地にする
 * - ロングタップ: その地点を目的地にする
 * - 地図をドラッグ・ピンチすると追従が止まり, "追従" ボタンで再開する
 */
class MapDebugActivity : ComponentActivity() {
    private lateinit var mapView: MapView
    private lateinit var unbind: () -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KkMap.init(this)

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        unbind = mapView.bindLifecycle(lifecycle)

        val followButton = Button(this).apply {
            text = "追従"
            visibility = Button.GONE
        }
        val hint = TextView(this).apply {
            text = "タップ: 現在地 / ロングタップ: 目的地"
            setBackgroundColor(0xCCFFFFFF.toInt())
            setPadding(24, 12, 24, 12)
        }

        setContentView(FrameLayout(this).apply {
            addView(mapView)
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { topMargin = 120; leftMargin = 24 })
            addView(followButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { bottomMargin = 160; rightMargin = 48 })
        })

        KkMapController.attach(mapView) { controller ->
            controller.onFollowModeChanged = { mode ->
                followButton.text = "追従: $mode"
            }
            followButton.text = "追従: ${controller.followMode}"
            followButton.visibility = Button.VISIBLE
            followButton.setOnClickListener { controller.resumeOrToggleFollow() }
            controller.map.addOnMapClickListener { latLng ->
                controller.setUserLocation(latLng)
                true
            }
            controller.map.addOnMapLongClickListener { latLng ->
                controller.setDestination(latLng)
                true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        unbind()
        super.onDestroy()
    }
}
