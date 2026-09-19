package io.github.whateverayn.kkmap.settings

import android.content.Context
import androidx.core.content.edit
import io.github.whateverayn.kkmap.location.LocationPriority
import io.github.whateverayn.kkmap.location.MinUpdateInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocationSourceKind(val label: String, val description: String) {
    FUSED("Fused Location Provider", "実機の測位 (GNSS / Wi-Fi / 基地局) を使う"),
    MANUAL("Manual (デバッグ)", "地図のロングタップで現在地を置く. 目的地への移動シミュレーションもできる"),
}

/** ピクチャーインピクチャー (PiP) の小窓の縦横比 (幅:高さ). Android の PiP は 1:2.39〜2.39:1 の範囲 */
enum class PipAspect(val width: Int, val height: Int, val label: String) {
    PORTRAIT(2, 3, "縦長 (2:3)"),
    SQUARE(1, 1, "正方形 (1:1)"),
    LANDSCAPE(3, 2, "横長 (3:2)"),
}

/** 速度計のメーターの最高速度 (対数スケールの右端, km/h) に指定できる範囲 */
val SPEED_METER_MAX_RANGE = 5..1000

data class Settings(
    val locationPriority: LocationPriority = LocationPriority.HIGH_ACCURACY,
    val locationIntervalMillis: Long = 5_000L,
    val locationMinUpdateInterval: MinUpdateInterval = MinUpdateInterval.SAME_AS_INTERVAL,
    val locationSource: LocationSourceKind = LocationSourceKind.FUSED,
    /** 速度計のメーターの最高速度 (km/h) */
    val speedMeterMaxKmh: Int = 130,
    val pipAspect: PipAspect = PipAspect.PORTRAIT,
)

/** SharedPreferences に保存する設定 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(KEY_PRIORITY, next.locationPriority.name)
            putLong(KEY_INTERVAL, next.locationIntervalMillis)
            putString(KEY_MIN_UPDATE, next.locationMinUpdateInterval.name)
            putString(KEY_SOURCE, next.locationSource.name)
            putInt(KEY_SPEED_METER_MAX, next.speedMeterMaxKmh)
            putString(KEY_PIP_ASPECT, next.pipAspect.name)
        }
        _settings.value = next
    }

    private fun load(): Settings {
        val default = Settings()
        return Settings(
            locationPriority = enumOrDefault(prefs.getString(KEY_PRIORITY, null), default.locationPriority),
            locationIntervalMillis = prefs.getLong(KEY_INTERVAL, default.locationIntervalMillis),
            locationMinUpdateInterval = enumOrDefault(prefs.getString(KEY_MIN_UPDATE, null), default.locationMinUpdateInterval),
            locationSource = enumOrDefault(prefs.getString(KEY_SOURCE, null), default.locationSource),
            speedMeterMaxKmh = prefs.getInt(KEY_SPEED_METER_MAX, default.speedMeterMaxKmh).coerceIn(SPEED_METER_MAX_RANGE),
            pipAspect = enumOrDefault(prefs.getString(KEY_PIP_ASPECT, null), default.pipAspect),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_PRIORITY = "location_priority"
        const val KEY_INTERVAL = "location_interval_ms"
        const val KEY_MIN_UPDATE = "location_min_update"
        const val KEY_SOURCE = "location_source"
        const val KEY_SPEED_METER_MAX = "speed_meter_max_kmh"
        const val KEY_PIP_ASPECT = "pip_aspect"
    }
}
