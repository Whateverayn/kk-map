package io.github.whateverayn.kkmap.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.whateverayn.kkmap.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whateverayn.kkmap.BuildConfig
import io.github.whateverayn.kkmap.updatePip
import io.github.whateverayn.kkmap.core.map.FollowMode
import io.github.whateverayn.kkmap.core.map.KkMapController
import io.github.whateverayn.kkmap.core.rail.RouteCandidate
import io.github.whateverayn.kkmap.location.DebugLocation
import io.github.whateverayn.kkmap.location.LocationFix
import io.github.whateverayn.kkmap.location.fusedLocationFlow
import io.github.whateverayn.kkmap.rail.RailData
import io.github.whateverayn.kkmap.rail.RailRepository
import io.github.whateverayn.kkmap.rail.RouteStore
import io.github.whateverayn.kkmap.settings.AppSettings
import io.github.whateverayn.kkmap.settings.LocationSourceKind
import io.github.whateverayn.kkmap.ui.route.RouteEditor
import io.github.whateverayn.kkmap.ui.settings.SettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LongPressTarget { DESTINATION, USER }

/** デバッグの移動シミュレーション速度 (m/s). 60km/h */
private const val SIMULATION_SPEED_MPS = 60_000.0 / 3600.0

/** [inPip] が true の間 (ピクチャーインピクチャーの小窓) は, 地図だけを出す */
@Composable
fun MapScreen(appSettings: AppSettings, inPip: Boolean = false) {
    val context = LocalContext.current
    val settings by appSettings.settings.collectAsStateWithLifecycle()

    var controller by remember { mutableStateOf<KkMapController?>(null) }
    var followMode by remember { mutableStateOf(FollowMode.FIT) }
    var destination by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var longPressTarget by rememberSaveable { mutableStateOf(LongPressTarget.DESTINATION) }
    var showRoute by rememberSaveable { mutableStateOf(false) }

    // 線路データと, 保存してある区間 (読み込みはバックグラウンド)
    val railData by produceState<RailData?>(null) { value = RailRepository.load(context) }
    val routeStore = remember { RouteStore(context) }
    var sections by remember { mutableStateOf<List<RouteCandidate>?>(null) }
    LaunchedEffect(railData) {
        val data = railData ?: return@LaunchedEffect
        if (sections == null) sections = withContext(Dispatchers.Default) { routeStore.load(data) }
    }
    val scope = rememberCoroutineScope()
    fun updateSections(next: List<RouteCandidate>) {
        sections = next
        scope.launch(Dispatchers.IO) { routeStore.save(next) }
    }
    // 経路があれば最後の降車駅が目的地. 無ければロングタップで置いた点
    val routeEnd = sections?.lastOrNull()?.to?.position
    val effectiveDestination = routeEnd?.let { doubleArrayOf(it.lat, it.lon) } ?: destination

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
    val fixFlow = remember(useManual, hasPermission, settings) {
        when {
            useManual -> DebugLocation.manual.fix.filterNotNull()
            hasPermission -> fusedLocationFlow(
                context,
                settings.locationPriority,
                settings.locationIntervalMillis,
                settings.locationMinUpdateInterval,
            )
            else -> emptyFlow()
        }
    }
    // collect はフォアグラウンド (STARTED) の間だけ. バックグラウンドでは測位しない
    val fix by fixFlow.collectAsStateWithLifecycle(initialValue = null)
    val speedEstimator = remember { SpeedEstimator() }
    val speed = remember(fix) { fix?.let(speedEstimator::update) }

    LaunchedEffect(controller, fix) {
        controller?.setUserLocation(fix?.let { LatLng(it.latitude, it.longitude) })
    }
    LaunchedEffect(controller, effectiveDestination?.toList()) {
        controller?.setDestination(effectiveDestination?.let { LatLng(it[0], it[1]) })
    }
    LaunchedEffect(controller, sections) {
        controller?.setRoute(sections?.map { s -> s.coordinates.map { LatLng(it.lat, it.lon) } })
    }

    // システムバーのアイコン色. 地図は常に明るい配色なので暗いアイコンにし, 設定画面ではテーマに合わせる
    val activity = LocalActivity.current
    val darkTheme = isSystemInDarkTheme()
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val lightBackground = !(showSettings || showRoute) || !darkTheme
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }

    // 追従中だけ, 他のアプリへ切り替えたときに自動で PiP の小窓にする
    LaunchedEffect(activity, followMode, settings.pipAspect) {
        activity?.updatePip(autoEnter = followMode != FollowMode.NONE, aspect = settings.pipAspect)
    }

    // 上部オーバーレイの下端と, ナビゲーションバーの高さ (px). 地図の UI とカメラの余白をこの内側に収める
    var overlayBottomPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarTopPx by remember { mutableIntStateOf(0) }
    val navigationBarPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    val bottomInsetPx = if (bottomBarTopPx > 0) rootHeightPx - bottomBarTopPx else navigationBarPx
    // 自動 PiP は追従中しか有効にしないので, PiP に入った時点で追従が外れていたら,
    // ホームに戻るスワイプが地図に触れて "地図を動かした" と判定されただけとみなし, 追従を再開する
    LaunchedEffect(controller, inPip) {
        if (inPip) controller?.resumeFollow()
    }

    LaunchedEffect(controller, overlayBottomPx, bottomInsetPx, inPip) {
        controller?.compact = inPip
        if (inPip) {
            controller?.setOverlayInsets(0, 0, 0, 0)
        } else {
            controller?.setOverlayInsets(0, overlayBottomPx, 0, bottomInsetPx)
        }
    }

    val currentLongPressTarget by rememberUpdatedState(if (useManual) longPressTarget else LongPressTarget.DESTINATION)

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootHeightPx = it.size.height }) {
        KkMapView(modifier = Modifier.fillMaxSize()) { c ->
            c.onFollowModeChanged = { followMode = it }
            c.map.addOnMapLongClickListener { p ->
                when (currentLongPressTarget) {
                    LongPressTarget.DESTINATION -> destination = doubleArrayOf(p.latitude, p.longitude)
                    LongPressTarget.USER -> DebugLocation.manual.set(p.latitude, p.longitude)
                }
                true
            }
            controller = c
        }

        // PiP の小窓では地図だけを出す (ボタン類は押せないし, 小窓を覆ってしまうため)
        if (inPip) return@Box

        Column(
            modifier = Modifier
                .onGloballyPositioned { overlayBottomPx = it.boundsInRoot().bottom.toInt() }
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
                // 背景を速度計のメーターとして左から塗る
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .speedMeter(speed, settings.speedMeterMaxKmh, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = statusText(fix, useManual, hasPermission, settings.locationPriority.label, settings.locationIntervalMillis),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    SpeedText(speed, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") }
                }
            }
            if (useManual) {
                DebugControls(
                    longPressTarget = longPressTarget,
                    onLongPressTargetChange = { longPressTarget = it },
                    destination = effectiveDestination,
                )
            }
        }

        // 下部: 右端に地図の操作ボタン, その下に経路の要約 (タップで編集)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(8.dp)
                .fillMaxWidth(),
        ) {
            MapButtons(
                followMode = followMode,
                onZoomIn = { controller?.zoomIn() },
                onZoomOut = { controller?.zoomOut() },
                onFollow = { controller?.resumeOrToggleFollow() },
            )
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .onGloballyPositioned { bottomBarTopPx = it.boundsInRoot().top.toInt() }
                    .clickable(enabled = railData != null) { showRoute = true },
            ) {
                Text(
                    text = routeSummary(railData, sections),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        val data = railData
        if (showRoute && data != null) {
            RouteEditor(
                data = data,
                sections = sections.orEmpty(),
                onSectionsChange = ::updateSections,
                onClose = { showRoute = false },
            )
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

private fun routeSummary(data: RailData?, sections: List<RouteCandidate>?): String = when {
    data == null || sections == null -> "線路データを読み込み中…"
    sections.isEmpty() -> "経路なし (タップして区間を追加)"
    else -> {
        val names = listOf(sections.first().from.name) + sections.map { it.to.name }
        val km = sections.sumOf { it.lengthMeters } / 1000
        "${names.joinToString(" → ")}  (%.1fkm)".format(km)
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
