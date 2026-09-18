package io.github.whateverayn.kkmap.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fused Location Provider の位置更新を Flow にしたもの.
 * collect している間だけ位置を要求し, collect をやめると要求も止まる.
 * 位置情報の権限は呼び出し側で取得済みであること.
 */
@SuppressLint("MissingPermission")
fun fusedLocationFlow(context: Context, priority: LocationPriority, intervalMillis: Long): Flow<LocationFix> =
    callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(priority.value, intervalMillis).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        // 最初の更新が来るまでのつなぎとして, 端末が持っている最後の位置を流す
        client.lastLocation.addOnSuccessListener { location -> location?.let { trySend(it.toFix()) } }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

private fun Location.toFix() = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    timeMillis = time,
)
