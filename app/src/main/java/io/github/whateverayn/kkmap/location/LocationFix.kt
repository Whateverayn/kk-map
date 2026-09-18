package io.github.whateverayn.kkmap.location

/** 位置情報1回分. 取得元 (Fused / 手動) に依存しない形 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度 (m). 不明なら null */
    val accuracyMeters: Float?,
    /** 取得時刻 (System.currentTimeMillis 基準) */
    val timeMillis: Long,
)
