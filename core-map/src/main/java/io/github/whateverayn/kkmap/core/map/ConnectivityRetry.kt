package io.github.whateverayn.kkmap.core.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre

/**
 * 通信が戻ったときに, 取得に失敗した地図タイルなどを MapLibre に取り直させる.
 *
 * MapLibre は取得に失敗したリクエストを, 時間が経っても自分からは取り直さない (実機で確認: 通信の遮断を解いてから
 * 110 秒待っても地図が出なかった). OS から接続状態の変化を知らされたときだけ取り直すので, 次の2つで知らせる.
 *
 * - インターネットに実際につながった (NET_CAPABILITY_VALIDATED) とき
 * - 画面を開いている間 [periodMillis] ごと. 駅のフリー Wi-Fi のログイン前や, 電波が弱くてタイムアウトしたときのように,
 *   OS から見た接続状態は変わらないまま通信だけが戻る場合のため. 失敗したリクエストを取り直すだけなので軽い
 *
 * ライフサイクルが STARTED の間だけ動く (バックグラウンドでは何もしない).
 */
class ConnectivityRetry(
    context: Context,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
) : DefaultLifecycleObserver {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                handler.post { nudge() }
            }
        }
    }

    private val periodic = object : Runnable {
        override fun run() {
            nudge()
            handler.postDelayed(this, periodMillis)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        connectivity.registerDefaultNetworkCallback(callback)
        handler.postDelayed(periodic, periodMillis)
    }

    override fun onStop(owner: LifecycleOwner) {
        connectivity.unregisterNetworkCallback(callback)
        handler.removeCallbacks(periodic)
    }

    /**
     * MapLibre に接続状態を知らせ直す. null は "OS の接続状態に従う" (既定の動作) で, 知らせ直すと
     * MapLibre は "つながった" とみなして, 失敗したリクエストを取り直す
     */
    private fun nudge() {
        MapLibre.setConnected(null)
    }

    private companion object {
        const val DEFAULT_PERIOD_MILLIS = 30_000L
    }
}
