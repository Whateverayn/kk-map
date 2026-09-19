package io.github.whateverayn.kkmap.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.whateverayn.kkmap.BuildConfig
import io.github.whateverayn.kkmap.location.LOCATION_INTERVAL_CHOICES_MILLIS
import io.github.whateverayn.kkmap.location.LOCATION_INTERVAL_DESCRIPTION
import io.github.whateverayn.kkmap.location.LocationPriority
import io.github.whateverayn.kkmap.location.MinUpdateInterval
import io.github.whateverayn.kkmap.settings.DataUsage
import io.github.whateverayn.kkmap.settings.DataUsageHistory
import io.github.whateverayn.kkmap.settings.LocationSourceKind
import io.github.whateverayn.kkmap.settings.PeriodUsage
import io.github.whateverayn.kkmap.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 地図の上に全面で重ねる設定画面 (画面遷移アニメーションを避けるため, ナビゲーションは使わない) */
@Composable
fun SettingsPanel(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("設定", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
            }

            DataUsageSection()

            if (BuildConfig.DEBUG) {
                SectionTitle("位置情報ソース (debug ビルドのみ)")
                LocationSourceKind.entries.forEach { kind ->
                    OptionRow(
                        selected = settings.locationSource == kind,
                        title = kind.label,
                        description = kind.description,
                        onClick = { onChange { it.copy(locationSource = kind) } },
                    )
                }
            }

            SectionTitle("Fused Location: Priority")
            LocationPriority.entries.forEach { priority ->
                OptionRow(
                    selected = settings.locationPriority == priority,
                    title = priority.label,
                    description = priority.description,
                    onClick = { onChange { it.copy(locationPriority = priority) } },
                )
            }

            SectionTitle("Fused Location: 更新間隔")
            Text(LOCATION_INTERVAL_DESCRIPTION, style = MaterialTheme.typography.bodySmall)
            LOCATION_INTERVAL_CHOICES_MILLIS.forEach { interval ->
                OptionRow(
                    selected = settings.locationIntervalMillis == interval,
                    title = "${interval / 1000} 秒",
                    description = null,
                    onClick = { onChange { it.copy(locationIntervalMillis = interval) } },
                )
            }

            SectionTitle("Fused Location: 最短受信間隔 (minUpdateIntervalMillis)")
            MinUpdateInterval.entries.forEach { minUpdate ->
                OptionRow(
                    selected = settings.locationMinUpdateInterval == minUpdate,
                    title = minUpdate.label,
                    description = minUpdate.description,
                    onClick = { onChange { it.copy(locationMinUpdateInterval = minUpdate) } },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OptionRow(selected: Boolean, title: String, description: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 通信量と地図キャッシュの大きさ. 開いている間, 起動してからの値は1秒ごと, 期間ごとの値は10秒ごとに更新する
 * (期間ごとの値は OS の記録の更新が遅いので, 頻繁に問い合わせても変わらない)
 */
@Composable
private fun DataUsageSection() {
    val context = LocalContext.current
    val usage by produceState(DataUsage.snapshot(context)) {
        while (true) {
            delay(1_000)
            value = DataUsage.snapshot(context)
        }
    }
    val history by produceState<DataUsageHistory?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { DataUsage.history(context) }
            delay(10_000)
        }
    }
    SectionTitle("通信量")
    Text(
        "このアプリの通信量 (地図タイル以外も含む. 測位は Google Play 開発者サービスの通信なので含まない). " +
            "単位は 2 進 (1 KiB = 1024 B). 起動してから / 端末を起動してから は TrafficStats, " +
            "期間ごとは OS の記録 (NetworkStatsManager) で, 記録は数分遅れて反映される",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("起動してから: 受信 ${DataUsage.format(usage.session.rx)} / 送信 ${DataUsage.format(usage.session.tx)}")
    Text("端末を起動してから: 受信 ${DataUsage.format(usage.sinceBoot.rx)} / 送信 ${DataUsage.format(usage.sinceBoot.tx)}")
    val h = history
    if (h == null) {
        Text("期間ごと: 取得中…")
    } else {
        PeriodRow("過去24時間", h.last24Hours, "1時間平均", 24.0)
        PeriodRow("過去30日", h.last30Days, "1日平均", 30.0)
        PeriodRow("インストールしてから", h.sinceInstall, "1日平均", h.daysSinceInstall.coerceAtLeast(1.0))
    }
    Text("地図キャッシュ (mbgl-offline.db): ${DataUsage.format(usage.mapCacheBytes)}")
}

@Composable
private fun PeriodRow(title: String, usage: PeriodUsage?, averageLabel: String, divisor: Double) {
    Spacer(Modifier.height(4.dp))
    if (usage == null) {
        Text("$title: 取得できません")
        return
    }
    Text("$title: 計 ${DataUsage.format(usage.total.total)}  ($averageLabel ${DataUsage.format(usage.total.total / divisor)})")
    Text(
        "  Wi-Fi ${DataUsage.format(usage.wifi.total)} / モバイル ${DataUsage.format(usage.mobile.total)}",
        style = MaterialTheme.typography.bodySmall,
    )
}
