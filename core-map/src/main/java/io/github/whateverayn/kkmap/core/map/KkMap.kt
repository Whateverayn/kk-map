package io.github.whateverayn.kkmap.core.map

import android.content.Context
import org.maplibre.android.MapLibre

object KkMap {
    /** 同梱している地理院 最適化ベクトルタイルの標準スタイル (tools/gsi-style/build_style.py で生成) */
    const val GSI_STD_STYLE_URI = "asset://gsi/std.json"

    /** MapView を生成する前に1回呼ぶ. 複数回呼んでもよい */
    fun init(context: Context) {
        MapLibre.getInstance(context.applicationContext)
    }
}
