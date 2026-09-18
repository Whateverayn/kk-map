package io.github.whateverayn.kkmap.core.map

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * MapView のライフサイクルメソッドを [lifecycle] に連動させる.
 * MapView.onCreate() は呼び出し側で先に済ませておくこと.
 *
 * 戻り値の関数を呼ぶと連動を解除し, MapView を破棄する (Compose の onDispose 用).
 */
fun MapView.bindLifecycle(lifecycle: Lifecycle): () -> Unit {
    val observer = MapViewLifecycleObserver(this)
    lifecycle.addObserver(observer)
    return {
        lifecycle.removeObserver(observer)
        observer.destroy()
    }
}

private class MapViewLifecycleObserver(private val mapView: MapView) : DefaultLifecycleObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStart(owner: LifecycleOwner) {
        mapView.onStart()
        started = true
    }

    override fun onResume(owner: LifecycleOwner) {
        mapView.onResume()
        resumed = true
    }

    override fun onPause(owner: LifecycleOwner) {
        mapView.onPause()
        resumed = false
    }

    override fun onStop(owner: LifecycleOwner) {
        mapView.onStop()
        started = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
    }

    fun destroy() {
        if (destroyed) return
        if (resumed) mapView.onPause()
        if (started) mapView.onStop()
        mapView.onDestroy()
        resumed = false
        started = false
        destroyed = true
    }
}
