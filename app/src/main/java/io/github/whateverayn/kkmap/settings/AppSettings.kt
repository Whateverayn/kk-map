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

data class Settings(
    val locationPriority: LocationPriority = LocationPriority.HIGH_ACCURACY,
    val locationIntervalMillis: Long = 5_000L,
    val locationMinUpdateInterval: MinUpdateInterval = MinUpdateInterval.SAME_AS_INTERVAL,
    val locationSource: LocationSourceKind = LocationSourceKind.FUSED,
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
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_PRIORITY = "location_priority"
        const val KEY_INTERVAL = "location_interval_ms"
        const val KEY_MIN_UPDATE = "location_min_update"
        const val KEY_SOURCE = "location_source"
    }
}
