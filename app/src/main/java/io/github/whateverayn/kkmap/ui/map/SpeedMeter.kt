package io.github.whateverayn.kkmap.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.github.whateverayn.kkmap.location.LocationFix
import io.github.whateverayn.kkmap.location.distanceMeters
import kotlin.math.ln

/** 速度 (km/h). [measured] が true なら測位で得た値, false なら前回の位置からの距離と時間で計算した値 */
data class SpeedReading(val kmh: Double, val measured: Boolean)

/**
 * 位置の更新ごとに速度を求める.
 * GNSS の測位なら速度が付いてくる (ドップラー効果から直接測った値) のでそれを使う.
 * 付いていなければ (Wi-Fi・基地局の測位, 手動の位置) 前回の位置との距離 ÷ 時間で計算する. 位置の誤差がそのまま速度のブレになる
 */
class SpeedEstimator {
    private var previous: LocationFix? = null
    private var last: SpeedReading? = null

    fun update(fix: LocationFix): SpeedReading? {
        val prev = previous
        if (prev != null && prev.timeMillis == fix.timeMillis) return last // 同じ位置が2度届いた
        previous = fix
        val speed = fix.speedMps?.let { SpeedReading(it * 3.6, measured = true) }
            ?: prev?.let {
                val seconds = (fix.timeMillis - it.timeMillis) / 1000.0
                if (seconds <= 0 || seconds > MAX_GAP_SECONDS) {
                    null
                } else {
                    val meters = distanceMeters(it.latitude, it.longitude, fix.latitude, fix.longitude)
                    SpeedReading(meters / seconds * 3.6, measured = false)
                }
            }
        last = speed
        return speed
    }

    private companion object {
        /** これより間が空いた2点からは速度を計算しない (止まっていたのか動いていたのか分からないため) */
        const val MAX_GAP_SECONDS = 120.0
    }
}

/**
 * 速度計のメーター: 背景を左から塗る. 対数スケール (徒歩の数 km/h と新幹線の数百 km/h を1本に収めるため).
 * 0 km/h で 0, [maxKmh] で端まで
 */
fun Modifier.speedMeter(speed: SpeedReading?, maxKmh: Int, color: Color): Modifier = drawBehind {
    val kmh = speed?.kmh ?: return@drawBehind
    val fraction = (ln(1 + kmh) / ln(1.0 + maxKmh)).coerceIn(0.0, 1.0)
    drawRect(color, size = Size(size.width * fraction.toFloat(), size.height))
}

/**
 * 速度の数字 (2行: 値と単位).
 * 前回の位置から計算した値 (測位で得た値ではない) は, 数字の前に "≈" を付けて薄い色にする
 */
@Composable
fun SpeedText(speed: SpeedReading?, modifier: Modifier = Modifier) {
    val alpha = if (speed?.measured == false) ESTIMATED_ALPHA else 1f
    Column(horizontalAlignment = Alignment.End, modifier = modifier.alpha(alpha)) {
        Text(
            text = when {
                speed == null -> "—"
                speed.measured -> "%.0f".format(speed.kmh)
                else -> "≈%.0f".format(speed.kmh)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text("km/h", style = MaterialTheme.typography.labelSmall)
    }
}

/** 計算した速度の文字の濃さ */
private const val ESTIMATED_ALPHA = 0.55f
