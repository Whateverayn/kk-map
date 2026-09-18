package io.github.whateverayn.kkmap.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whateverayn.kkmap.BuildConfig
import io.github.whateverayn.kkmap.core.map.KkMapController
import io.github.whateverayn.kkmap.location.DebugLocation
import io.github.whateverayn.kkmap.location.LocationFix
import io.github.whateverayn.kkmap.location.fusedLocationFlow
import io.github.whateverayn.kkmap.settings.AppSettings
import io.github.whateverayn.kkmap.settings.LocationSourceKind
import io.github.whateverayn.kkmap.ui.settings.SettingsPanel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LongPressTarget { DESTINATION, USER }

/** デバッグの移動シミュレーション速度 (m/s). 60km/h */
private const val SIMULATION_SPEED_MPS = 60_000.0 / 3600.0

@Composable
fun MapScreen(appSettings: AppSettings) {
    val context = LocalContext.current
    val settings by appSettings.settings.collectAsStateWithLifecycle()

    var controller by remember { mutableStateOf<KkMapController?>(null) }
    var following by remember { mutableStateOf(true) }
    var destination by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var longPressTarget by rememberSaveable { mutableStateOf(LongPressTarget.DESTINATION) }

    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasPermission = hasLocationPermission(context) }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    val useManual = BuildConfig.DEBUG && settings.locationSource == LocationSourceKind.MANUAL
    val fixFlow = remember(useManual, hasPermission, settings.locationPriority, settings.locationIntervalMillis) {
        when {
            useManual -> DebugLocation.manual.fix.filterNotNull()
            hasPermission -> fusedLocationFlow(context, settings.locationPriority, settings.locationIntervalMillis)
            else -> emptyFlow()
        }
    }
    // collect はフォアグラウンド (STARTED) の間だけ. バックグラウンドでは測位しない
    val fix by fixFlow.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(controller, fix) {
        controller?.setUserLocation(fix?.let { LatLng(it.latitude, it.longitude) })
    }
    LaunchedEffect(controller, destination) {
        controller?.setDestination(destination?.let { LatLng(it[0], it[1]) })
    }

    val currentLongPressTarget by rememberUpdatedState(if (useManual) longPressTarget else LongPressTarget.DESTINATION)

    Box(Modifier.fillMaxSize()) {
        KkMapView(modifier = Modifier.fillMaxSize()) { c ->
            c.onFollowModeChanged = { following = it }
            c.map.addOnMapLongClickListener { p ->
                when (currentLongPressTarget) {
                    LongPressTarget.DESTINATION -> destination = doubleArrayOf(p.latitude, p.longitude)
                    LongPressTarget.USER -> DebugLocation.manual.set(p.latitude, p.longitude)
                }
                true
            }
            controller = c
        }

        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = statusText(fix, useManual, hasPermission, settings.locationPriority.label, settings.locationIntervalMillis),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.heightIn(min = 48.dp).padding(4.dp),
                    ) { Text("設定") }
                }
            }
            if (useManual) {
                DebugControls(
                    longPressTarget = longPressTarget,
                    onLongPressTargetChange = { longPressTarget = it },
                    destination = destination,
                )
            }
        }

        if (!following) {
            Button(
                onClick = { controller?.followMode = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
            ) { Text("追従") }
        }

        if (showSettings) {
            SettingsPanel(
                settings = settings,
                onChange = appSettings::update,
                onClose = { showSettings = false },
            )
        }
    }
}

@Composable
private fun DebugControls(
    longPressTarget: LongPressTarget,
    onLongPressTargetChange: (LongPressTarget) -> Unit,
    destination: DoubleArray?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(4.dp),
        ) {
            Text("ロングタップ:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            TargetButton("目的地", longPressTarget == LongPressTarget.DESTINATION) {
                onLongPressTargetChange(LongPressTarget.DESTINATION)
            }
            TargetButton("現在地", longPressTarget == LongPressTarget.USER) {
                onLongPressTargetChange(LongPressTarget.USER)
            }
            OutlinedButton(
                enabled = destination != null,
                onClick = {
                    val manual = DebugLocation.manual
                    if (manual.isSimulating) {
                        manual.stopSimulation()
                    } else if (destination != null) {
                        manual.simulateTo(DebugLocation.scope, destination[0], destination[1], SIMULATION_SPEED_MPS)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("移動/停止") }
        }
    }
}

@Composable
private fun TargetButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

private fun statusText(
    fix: LocationFix?,
    useManual: Boolean,
    hasPermission: Boolean,
    priorityLabel: String,
    intervalMillis: Long,
): String {
    val source = if (useManual) "Manual" else "$priorityLabel / ${intervalMillis / 1000}s"
    if (!useManual && !hasPermission) return "位置情報の権限がありません\n$source"
    if (fix == null) return "測位待ち\n$source"
    val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(fix.timeMillis))
    val accuracy = fix.accuracyMeters?.let { "±%.0fm".format(it) } ?: "±?m"
    return "$accuracy  $time\n$source"
}

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
