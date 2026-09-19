package io.github.whateverayn.kkmap.location

import com.google.android.gms.location.Priority

/** Fused Location Provider の Priority. 設定画面に説明付きで出す */
enum class LocationPriority(val value: Int, val label: String, val description: String) {
    HIGH_ACCURACY(
        Priority.PRIORITY_HIGH_ACCURACY,
        "PRIORITY_HIGH_ACCURACY",
        "GNSS (GPS 等) を積極的に使う. 精度は数m. 電池消費は最大. 地下・トンネルでは Wi-Fi/基地局にフォールバックする",
    ),
    BALANCED_POWER_ACCURACY(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        "PRIORITY_BALANCED_POWER_ACCURACY",
        "主に Wi-Fi・基地局を使い, GNSS はほぼ使わない. 精度は約100m (街区レベル). 電池消費は中",
    ),
    LOW_POWER(
        Priority.PRIORITY_LOW_POWER,
        "PRIORITY_LOW_POWER",
        "基地局中心. 精度は約10km (市レベル). 電池消費は小. 駅間の追従には粗すぎる",
    ),
    PASSIVE(
        Priority.PRIORITY_PASSIVE,
        "PRIORITY_PASSIVE",
        "自分からは測位しない. 他のアプリが測位した結果だけを受け取る. 電池消費はほぼゼロだが, 更新は他アプリ次第",
    ),
}

/** 位置更新の間隔の選択肢 */
val LOCATION_INTERVAL_CHOICES_MILLIS: List<Long> = listOf(1_000L, 5_000L, 15_000L, 60_000L)

const val LOCATION_INTERVAL_DESCRIPTION =
    "LocationRequest の希望更新間隔. 短いほど追従が滑らかだが電池を使う. " +
        "実際の間隔は Priority や他アプリの要求によって前後する. " +
        "PASSIVE では測位を起こさないので, 受け取る頻度の上限としてだけ効く"

/** LocationRequest の minUpdateIntervalMillis (受け取る最短間隔) */
enum class MinUpdateInterval(val label: String, val description: String) {
    SAME_AS_INTERVAL(
        "更新間隔と同じ",
        "他のアプリがもっと頻繁に測位していても, 更新間隔より短い間隔では受け取らない (既定の動作)",
    ),
    ONE_SECOND(
        "1 秒",
        "他のアプリが測位していれば, 更新間隔によらず最短1秒ごとに受け取る. 位置ロガーと PASSIVE を組み合わせるときに向く. " +
            "0 (無制限) にしないのは, 高頻度に測位するアプリがあると受け取るたびに地図が動いて描画の負荷が増えるため",
    ),
    ;

    fun millis(intervalMillis: Long): Long = when (this) {
        SAME_AS_INTERVAL -> intervalMillis
        ONE_SECOND -> minOf(1_000L, intervalMillis)
    }
}
