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
    "LocationRequest の希望更新間隔. 短いほど追従が滑らかだが電池を使う." +
        "実際の間隔は Priority や他アプリの要求によって前後する"
