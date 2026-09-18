package io.github.whateverayn.kkmap.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 手動の位置情報 (デバッグ用).
 * 地図のロングタップで位置を置いたり, 目的地へ一定速度で移動させたりして, 室内で追従を確認する.
 */
class ManualLocation {
    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    private var simulation: Job? = null
    val isSimulating: Boolean get() = simulation?.isActive == true

    fun set(latitude: Double, longitude: Double) {
        stopSimulation()
        _fix.value = LocationFix(latitude, longitude, accuracyMeters = 0f, timeMillis = System.currentTimeMillis())
    }

    /** 現在の手動位置から (toLat, toLon) まで, [speedMps] で直線移動させる */
    fun simulateTo(scope: CoroutineScope, toLat: Double, toLon: Double, speedMps: Double, stepMillis: Long = 1_000L) {
        val start = _fix.value ?: return
        stopSimulation()
        simulation = scope.launch {
            val total = distanceMeters(start.latitude, start.longitude, toLat, toLon)
            var travelled = 0.0
            while (isActive && travelled < total) {
                delay(stepMillis)
                travelled = minOf(total, travelled + speedMps * stepMillis / 1000.0)
                val t = if (total == 0.0) 1.0 else travelled / total
                _fix.value = LocationFix(
                    latitude = start.latitude + (toLat - start.latitude) * t,
                    longitude = start.longitude + (toLon - start.longitude) * t,
                    accuracyMeters = 0f,
                    timeMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    fun stopSimulation() {
        simulation?.cancel()
        simulation = null
    }
}

/** 2点間の距離 (m). ハバーサイン式 */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}
