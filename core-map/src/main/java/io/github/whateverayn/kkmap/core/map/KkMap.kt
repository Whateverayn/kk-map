package io.github.whateverayn.kkmap.core.map

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

object KkMap {
    /** 同梱している地理院 最適化ベクトルタイルの標準スタイル (tools/gsi-style/build_style.py で生成) */
    const val GSI_STD_STYLE_URI = "asset://gsi/std.json"

    /**
     * 地図キャッシュ (一度表示したタイルを端末に残すもの) の上限. MapLibre の既定は 50MB.
     * 上限を超えると, 使われていない古いものから自動で消える. 移動中の圏外に備えて多めにする
     */
    const val AMBIENT_CACHE_BYTES = 1L shl 30 // 1 GiB

    private var initialized = false

    /** MapView を生成する前に1回呼ぶ. 複数回呼んでもよい */
    fun init(context: Context) {
        MapLibre.getInstance(context.applicationContext)
        if (initialized) return
        initialized = true
        OfflineManager.getInstance(context.applicationContext)
            .setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit // 失敗しても既定の上限で動くので無視する
            })
    }
}
