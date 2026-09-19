package io.github.whateverayn.kkmap.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.whateverayn.kkmap.core.map.ConnectivityRetry
import io.github.whateverayn.kkmap.core.map.KkMap
import io.github.whateverayn.kkmap.core.map.KkMapController
import io.github.whateverayn.kkmap.core.map.bindLifecycle
import org.maplibre.android.maps.MapView

/** :core-map の MapView を Compose に置くだけの薄いラッパー */
@Composable
fun KkMapView(
    modifier: Modifier = Modifier,
    onReady: (KkMapController) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnReady by rememberUpdatedState(onReady)
    val mapView = remember {
        KkMap.init(context)
        MapView(context).also { it.onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val unbind = mapView.bindLifecycle(lifecycle)
        // 通信が戻ったら失敗したタイルを取り直させる (アプリを再起動しなくても地図が出るように)
        val retry = ConnectivityRetry(context)
        lifecycle.addObserver(retry)
        onDispose {
            lifecycle.removeObserver(retry)
            unbind()
        }
    }
    DisposableEffect(mapView) {
        KkMapController.attach(mapView) { currentOnReady(it) }
        onDispose { }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}
